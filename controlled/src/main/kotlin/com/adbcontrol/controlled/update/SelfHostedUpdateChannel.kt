package com.adbcontrol.controlled.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.executor.ShizukuExecutor
import com.adbcontrol.shared.model.AppConfig
import com.adbcontrol.shared.model.UpdateCheckResponse
import com.adbcontrol.shared.model.UpdateResultReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest

/**
 * 自建更新通道。README 11.2。
 *
 * 流程:
 * 1. GET {serverUrl}/update/check → [UpdateCheckResponse]
 *    (serverUrl/deviceId 来自配对时持久化的 [AppConfig],未配对/老数据无 serverUrl 时静默跳过)
 * 2. 优先下载差分包(patchUrl),bsdiff 应用 → APK;失败回退全量 APK(fullApkUrl)
 *    GitHub Release 链接走 [GitHubFastDownloader](优选 IP + 加速代理回退)
 * 3. sha256 校验通过
 * 4. Shizuku 可用 → `pm install -S` stdin 流式静默安装(绕开私有目录权限)
 *    Shizuku 不可用 → FileProvider + 系统安装确认
 * 5. 安装结果 POST /update/report 上报(便于服务端统计分发情况)
 */
class SelfHostedUpdateChannel(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val shizukuExecutor: ShizukuExecutor,
    private val configStore: ConfigStore,
    private val githubDownloader: GitHubFastDownloader,
) : UpdateChannel {

    private val cacheDir: File by lazy {
        File(context.cacheDir, "updates").apply { mkdirs() }
    }

    /** 当前配对配置;未配对或 serverUrl 为空返回 null(更新通道关闭)。 */
    private fun currentConfig(): AppConfig? =
        runCatching { configStore.load() }.getOrNull()?.takeIf { it.serverUrl.isNotBlank() }

    override suspend fun check(): UpdateCheckResponse? = withContext(Dispatchers.IO) {
        val diag = StringBuilder()
        fun diag(s: String) { Log.i(TAG, s); diag.append(s).append('\n') }
        val cfg = currentConfig()
        if (cfg == null) {
            diag("check: currentConfig()=null (未配对或 serverUrl 为空),更新通道关闭")
            writeDiagFile(diag.toString())
            return@withContext null
        }
        diag("check: cfg loaded, deviceId=${cfg.deviceId}, serverUrl=${cfg.serverUrl}, curVc=${currentVersionCode()}, curVn=${currentVersionName()}")
        val serverUrl = cfg.serverUrl.trim().trimEnd('/')
        val queryUrl = android.net.Uri.parse(serverUrl).buildUpon()
            .appendPath("update").appendPath("check")
            .appendQueryParameter("deviceId", cfg.deviceId)
            .appendQueryParameter("currentVersionCode", currentVersionCode().toString())
            .appendQueryParameter("currentVersionName", currentVersionName())
            .appendQueryParameter("channel", "stable")
            .build().toString()
        diag("check: queryUrl=$queryUrl")
        runCatching {
            httpClient.newCall(Request.Builder().url(queryUrl).build()).execute().use { resp ->
                diag("check: http ${resp.code}")
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string()?.take(200).orEmpty()
                    diag("check: http not 2xx, body=$errBody")
                    writeDiagFile(diag.toString())
                    return@withContext null
                }
                val body = resp.body?.string().orEmpty()
                diag("check: body=${body.take(300)}")
                val parsed = json.decodeFromString(UpdateCheckResponse.serializer(), body)
                diag("check: parsed hasUpdate=${parsed.hasUpdate} latestVc=${parsed.latestVersionCode} latestVn=${parsed.latestVersionName}")
                writeDiagFile(diag.toString())
                parsed.takeIf { it.hasUpdate }
            }
        }.getOrElse {
            diag("check failed: ${it.javaClass.simpleName}: ${it.message}")
            writeDiagFile(diag.toString())
            null
        }
    }

    /** 诊断日志写到 cache/ota_diag.txt(adb run-as 可读)。 */
    private fun writeDiagFile(content: String) {
        runCatching {
            File(context.cacheDir, "ota_diag.txt").writeText(content)
        }
    }

    /** 安装结果上报到 /update/report。失败仅记日志,不影响主流程。 */
    suspend fun report(
        versionCode: Int,
        success: Boolean,
        errorMsg: String?,
        durationMs: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val cfg = currentConfig() ?: return@withContext false
        val report = UpdateResultReport(
            deviceId = cfg.deviceId,
            versionCode = versionCode,
            success = success,
            errorMsg = errorMsg?.take(300),
            durationMs = durationMs,
            timestamp = System.currentTimeMillis(),
        )
        val body = json.encodeToString(UpdateResultReport.serializer(), report)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(cfg.serverUrl.trim().trimEnd('/') + "/update/report")
            .post(body)
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.w(TAG, "report failed: ${it.message}")
            false
        }
    }

    override suspend fun download(info: UpdateCheckResponse, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            // 优先差分包(bsdiff 未集成时 patch 即原文件,sha256 必不匹配 → 自动回退全量)
            val patchUrl = info.patchUrl
            if (patchUrl != null) {
                runCatching {
                    val patchFile = fetch(patchUrl, "update.patch", onProgress)
                    val patched = applyBsdiff(patchFile)
                    if (verifySha256(patched, info.sha256)) return@withContext patched
                    Log.w(TAG, "patched apk sha256 mismatch, fallback to full")
                }.onFailure { Log.w(TAG, "patch apply failed, fallback to full", it) }
            }
            // 全量 fallback
            val fullUrl = info.fullApkUrl ?: error("no fullApkUrl and patch failed")
            val apk = fetch(fullUrl, "update.apk", onProgress)
            if (!verifySha256(apk, info.sha256)) error("sha256 mismatch")
            apk
        }

    /**
     * GitHub 链接走加速下载器(带自有后端中转源),其他(自有后端/R2)直连。
     * backendBase 传配对 serverUrl:下载器会把 {server}/update/apk?url= 中转源置顶竞速,
     * 且赢家传输中断时自动换源重试 —— 解决直连/公共代理大文件传输被掐断的问题。
     */
    private suspend fun fetch(url: String, name: String, onProgress: (Int) -> Unit): File {
        val target = File(cacheDir, name)
        return if (isGitHubUrl(url)) {
            githubDownloader.download(url, target, onProgress, currentConfig()?.serverUrl)
        } else {
            downloadFile(httpClient, url, target, onProgress)
        }
    }

    override suspend fun install(apk: File): UpdateChannel.InstallResult = withContext(Dispatchers.IO) {
        if (shizukuExecutor.isAvailable()) {
            // Shizuku 静默安装:pm install -S stdin 流式喂包
            // 根因修复:旧实现 `pm install -r <path>` 必然失败——
            // APK 在本应用私有缓存目录(0700,仅自身可读),而 Shizuku 执行
            // 进程是 shell uid,无权读路径 → Permission denied。
            // 流式安装由 App 进程读自己的文件并通过 stdin 传给 Shizuku 侧 pm,绕开文件权限。
            val result = shizukuExecutor.installApkStreamed(apk, "update-install")
            if (result.success) {
                UpdateChannel.InstallResult(success = true, message = "installed via Shizuku (streamed)")
            } else {
                UpdateChannel.InstallResult(success = false, message = result.output)
            }
        } else {
            // 无 Shizuku:FileProvider 共享 APK 拉起系统安装确认(需用户手动确认,非静默)
            val prompted = promptUserInstall(apk)
            UpdateChannel.InstallResult(
                success = false,
                message = if (prompted) "已拉起系统安装器,请在手机通知栏/界面确认安装(需一次人工授权)"
                    else "无安装器可用,请开启 Shizuku 以支持静默安装",
            )
        }
    }

    /** 拉起系统包安装器。返回是否成功发出 Intent。需用户授予"安装未知应用"权限一次。 */
    private fun promptUserInstall(apk: File): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.e(TAG, "promptUserInstall failed", it)
            false
        }
    }

    /**
     * 应用 bsdiff 差分包。
     * TODO: 引入 bsdiff 库(版本目录暂无),当前直接返回 patch 文件占位
     * (sha256 必不匹配 → 调用方自动回退全量包,功能无损)。
     */
    private fun applyBsdiff(patchFile: File): File {
        Log.w(TAG, "bsdiff not yet integrated, using patch as-is (will fail sha256 → fallback)")
        return patchFile
    }

    private fun isGitHubUrl(url: String): Boolean =
        url.contains("github.com", ignoreCase = true) ||
            url.contains("githubusercontent.com", ignoreCase = true)

    private fun downloadFile(
        c: OkHttpClient,
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
    ): File {
        target.parentFile?.mkdirs()
        c.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("download $url failed ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = body.contentLength()
            target.outputStream().use { out ->
                val source = body.byteStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    read += n
                    if (total > 0) onProgress(((read * 100) / total).toInt())
                }
            }
        }
        return target
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected.removePrefix("sha256:"), ignoreCase = true)
    }

    private fun currentVersionCode(): Int =
        com.adbcontrol.controlled.ControlledApp.appVersionCode(context).toInt()

    private fun currentVersionName(): String =
        com.adbcontrol.controlled.ControlledApp.appVersionName(context)

    companion object { private const val TAG = "SelfHostedUpdate" }
}
