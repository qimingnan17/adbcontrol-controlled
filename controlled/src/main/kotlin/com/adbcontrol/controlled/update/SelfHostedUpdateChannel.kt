package com.adbcontrol.controlled.update

import android.content.Context
import android.util.Log
import com.adbcontrol.controlled.executor.ShizukuExecutor
import com.adbcontrol.shared.model.UpdateCheckRequest
import com.adbcontrol.shared.model.UpdateCheckResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * 自建更新通道。README 11.2。
 *
 * 流程:
 * 1. GET backend /update/check → [UpdateCheckResponse]
 * 2. 优先下载差分包(patchUrl),bsdiff 应用 → APK
 *    失败回退全量 APK(fullApkUrl)
 * 3. sha256 校验通过
 * 4. Shizuku 可用 → `pm install -r` 静默安装
 *    Shizuku 不可用 → 弹系统安装确认 Intent
 */
class SelfHostedUpdateChannel(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val shizukuExecutor: ShizukuExecutor,
) : UpdateChannel {

    private val cacheDir: File by lazy {
        File(context.cacheDir, "updates").apply { mkdirs() }
    }

    /** 后端服务器 URL(配对时持久化,这里从 DataStore/ConfigStore 注入;TODO 接入)。 */
    var serverUrl: String = ""

    override suspend fun check(): UpdateCheckResponse? = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext null
        val request = UpdateCheckRequest(
            deviceId = currentDeviceId(),
            currentVersionCode = currentVersionCode(),
            currentVersionName = currentVersionName(),
        )
        val query = buildString {
            append(serverUrl.trimEnd('/')).append("/update/check")
            append("?deviceId=").append(request.deviceId)
            append("&currentVersionCode=").append(request.currentVersionCode)
            append("&currentVersionName=").append(request.currentVersionName)
            append("&channel=").append(request.channel)
        }
        runCatching {
            httpClient.newCall(Request.Builder().url(query).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                json.decodeFromString(UpdateCheckResponse.serializer(), body)
                    .takeIf { it.hasUpdate }
            }
        }.getOrElse {
            Log.e(TAG, "check failed", it); null
        }
    }

    override suspend fun download(info: UpdateCheckResponse, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        // 优先差分包
        val patchUrl = info.patchUrl
        if (patchUrl != null) {
            runCatching {
                val patchFile = downloadFile(patchUrl, "update.patch", onProgress)
                val patched = applyBsdiff(patchFile)
                if (verifySha256(patched, info.sha256)) return@withContext patched
                Log.w(TAG, "patched apk sha256 mismatch, fallback to full")
            }.onFailure { Log.w(TAG, "patch apply failed, fallback to full", it) }
        }
        // 全量 fallback
        val fullUrl = info.fullApkUrl ?: error("no fullApkUrl and patch failed")
        val apk = downloadFile(fullUrl, "update.apk", onProgress)
        if (!verifySha256(apk, info.sha256)) error("sha256 mismatch")
        apk
    }

    override suspend fun install(apk: File): UpdateChannel.InstallResult = withContext(Dispatchers.IO) {
        if (shizukuExecutor.isAvailable()) {
            // Shizuku 静默安装:pm install -r <path>
            val result = shizukuExecutor.execShell("pm install -r ${apk.absolutePath}", "update-install")
            if (result.success) {
                UpdateChannel.InstallResult(success = true, message = "installed via Shizuku")
            } else {
                UpdateChannel.InstallResult(success = false, message = result.output)
            }
        } else {
            // 弹系统安装确认 Intent
            promptUserInstall(apk)
            UpdateChannel.InstallResult(success = false, message = "prompted user install (no Shizuku)")
        }
    }

    private fun promptUserInstall(apk: File) {
        // TODO: 无 Shizuku 时弹系统安装确认。需声明 FileProvider 共享 file:// 给 PackageInstaller。
        // 主路径为 Shizuku 静默安装,此处仅记日志占位。
        Log.i(TAG, "promptUserInstall placeholder for ${apk.absolutePath}")
    }

    /**
     * 应用 bsdiff 差分包。
     * TODO: 引入 bsdiff 库(版本目录暂无),当前直接返回 patch 文件占位。
     */
    private fun applyBsdiff(patchFile: File): File {
        Log.w(TAG, "bsdiff not yet integrated, using patch as-is (will fail sha256 → fallback)")
        return patchFile
    }

    private fun downloadFile(url: String, name: String, onProgress: (Int) -> Unit): File {
        val target = File(cacheDir, name)
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("download $url failed ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = body.contentLength()
            target.outputStream().use { out ->
                val source = body.byteStream()
                val buffer = ByteArray(8192)
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
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected.removePrefix("sha256:"), ignoreCase = true)
    }

    private fun currentDeviceId(): String = "device-local" // TODO: 从 ConfigStore 注入
    private fun currentVersionCode(): Int =
        com.adbcontrol.controlled.ControlledApp.appVersionCode(context).toInt()
    private fun currentVersionName(): String =
        com.adbcontrol.controlled.ControlledApp.appVersionName(context)

    companion object { private const val TAG = "SelfHostedUpdate" }
}
