package com.adbcontrol.controlled.executor

import android.os.ParcelFileDescriptor
import android.util.Log
import com.adbcontrol.controlled.oem.MiuiAdapter
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.ExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku 主桥接执行器。README 3.2 / 3.3。
 *
 * - 通过 Shizuku Binder 拿到 shell 进程上下文,执行 `am`/`pm`/`input`/`settings`/`screencap` 等命令
 * - 等价 ADB 能力但不需要 root
 * - 监听 binder 死亡与权限变化,失效时上报(供主控端能力雷达)
 *
 * MIUI 专项:MIUI 13+ Shizuku 通过无线调试启动时,"USB 调试(安全设置)"开关未开
 * 会导致 Shizuku 看似已授权但 `input` 命令无效。检测失败时由 [miuiFailureHint] 引导用户。
 *
 * 实现:Shizuku v13.1.5 起 `rikka.shizuku.Shizuku.newProcess` 为 private,
 * 改为通过 `IShizukuService.Stub.asInterface(Shizuku.getBinder())` 直接调用 AIDL。
 *
 * 线程模型:[execute] 在协程中调用,内部 [IRemoteProcess.waitFor] 阻塞,需在 IO 调度器执行。
 */
@Singleton
class ShizukuExecutor @Inject constructor(
    private val miuiAdapter: MiuiAdapter,
) : CommandExecutor {

    override val name: String = "Shizuku"

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        availabilityListeners.forEach { it() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        availabilityListeners.forEach { it() }
    }

    // H7: forEach 与 add 跨线程并发可能触发 ConcurrentModificationException,
    // 改用 CopyOnWriteArrayList 保证遍历稳定性。
    private val availabilityListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 在 Application.onCreate 中绑定 Shizuku binder,注册死亡/恢复回调。
     * README 3.2.2 示例 Shizuku.bindSenderService() 等价。
     */
    fun bind() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }.onFailure { Log.e(TAG, "bind Shizuku failed", it) }
    }

    /** 注册可用性变化监听(供健康上报与主控端雷达刷新)。 */
    fun observeAvailability(listener: () -> Unit) {
        availabilityListeners.add(listener)
    }

    /** README 3.2.3: Shizuku 状态分四档。 */
    fun state(): ShizukuState {
        // H18: Shizuku SDK 未安装或 binder 异常时 pingBinder()/checkSelfPermission()
        // 可能抛 IllegalStateException,统一外层包 runCatching 兜底为 NOT_INSTALLED。
        return runCatching {
            if (!Shizuku.pingBinder()) {
                // 区分未安装:Shizuku 未安装时 pingBinder 抛异常或返回 false
                return@runCatching runCatching { Shizuku.checkSelfPermission() }
                    .map { ShizukuState.NOT_RUNNING }
                    .getOrElse { ShizukuState.NOT_INSTALLED }
            }
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ShizukuState.CONNECTED
            } else {
                ShizukuState.NOT_AUTHORIZED
            }
        }.getOrElse { ShizukuState.NOT_INSTALLED }
    }

    /**
     * MIUI 上 Shizuku 检测失败时的引导文案。
     *
     * 当 [state] != CONNECTED 且当前为 MIUI 且"USB 调试(安全设置)"开关未开时,返回
     * 引导用户去开发者选项打开该开关的文案(否则 input 命令在 MIUI 上无效)。
     * 其他情况(已连接 / 非 MIUI / USB 安全调试已开)返回 null,由调用方走通用提示。
     */
    fun miuiFailureHint(): String? {
        if (state() == ShizukuState.CONNECTED) return null
        return miuiAdapter.miuiShizukuHint()
    }

    enum class ShizukuState { CONNECTED, NOT_AUTHORIZED, NOT_RUNNING, NOT_INSTALLED }

    override fun isAvailable(): Boolean = state() == ShizukuState.CONNECTED

    override fun supports(command: Command): Boolean =
        CommandShellBuilder.isShellExpressible(command)

    override suspend fun execute(command: Command, commandId: String): ExecutionResult {
        if (!isAvailable()) {
            return fail(commandId, "SHIZUKU_UNAVAILABLE")
        }
        val started = System.currentTimeMillis()

        // 截屏单独处理:screencap -p 直接把 PNG 写到 stdout,捕获字节流
        // (旧实现落盘 /sdcard 再读,受 scoped storage 限制且文件残留)
        if (command.category == com.adbcontrol.shared.model.CommandCategory.APP &&
            command.action == "screencap"
        ) {
            val png = captureScreenBytes()
            val duration = System.currentTimeMillis() - started
            return if (png != null) ok(commandId, "screenshot bytes=${png.size}", duration)
            else fail(commandId, "screencap failed", duration)
        }

        val shell = CommandShellBuilder.build(command)
            ?: return noPath(commandId)
        return runShell(arrayOf("sh", "-c", shell), commandId, started)
    }

    /**
     * 截屏并返回 PNG 字节(`screencap -p` 输出到 stdout)。
     * 返回 null 表示失败(无 binder / 超时 / 空输出)。供 CommandHandler 截图链路调用。
     */
    suspend fun captureScreenBytes(timeoutMs: Long = 12_000L): ByteArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val binder = Shizuku.getBinder() ?: return@runCatching null
                val service = IShizukuService.Stub.asInterface(binder)
                val process = service.newProcess(arrayOf("screencap", "-p"), null, null)
                val outPfd: ParcelFileDescriptor? = runCatching { process.inputStream }.getOrNull()
                try {
                    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
                    try {
                        val readFuture = pool.submit<kotlin.Pair<ByteArray, Int>> {
                            val bytes = FileInputStream(outPfd!!.fileDescriptor).use { ins ->
                                ins.readBytes()
                            }
                            kotlin.Pair(bytes, process.waitFor())
                        }
                        val (bytes, code) = withTimeoutOrNull(timeoutMs) { readFuture.get() }
                            ?: run {
                                runCatching { process.destroy() }
                                return@runCatching null
                            }
                        if (code != 0 || bytes.isEmpty()) null else bytes
                    } finally {
                        pool.shutdownNow()
                    }
                } finally {
                    runCatching { outPfd?.close() }
                    runCatching { process.destroy() }
                }
            }.getOrElse {
                Log.e(TAG, "captureScreenBytes failed", it)
                null
            }
        }

    /** 执行任意 shell,公开供 [com.adbcontrol.controlled.update.SelfHostedUpdateChannel] 静默安装用。 */
    suspend fun execShell(shellLine: String, commandId: String = "shell"): ExecutionResult =
        runShell(arrayOf("sh", "-c", shellLine), commandId, System.currentTimeMillis())

    /**
     * OTA 静默安装:三步 session 模式,通过 stdin 流式喂包绕开文件路径权限。
     *
     * **为什么不能用 `pm install -r -S <size>` 单命令?**
     * 实测 vivo/MIUI 等国产 ROM 的 pm 不支持单命令 stdin 模式,直接打印 help 并
     * exit=255(表现为 exit=1 stderr=空)。必须用三步 session:
     *
     * 1. `pm install-create -r` → 返回 session id(从 "Success: created install session [NNN]" 解析)
     * 2. `pm install-write -S <size> <id> base.apk -` → 从 stdin 读指定字节写入 session
     * 3. `pm install-commit <id>` → 提交安装
     *
     * **文件权限问题**:APK 在本应用私有缓存目录(0700 仅自身可读),Shizuku 的 shell
     * uid 无权读取。install-write 的 `-S` + stdin 模式由本 App 进程(对自己缓存有读权限)
     * 把字节写入远程进程 stdin,绕开 filesystem 权限。
     *
     * **管道死锁防护**:install-write 阶段 pm 的 stdout/stderr 缓冲区有限(约 64KB),
     * 必须并发:写 stdin 的同时读 stdout/stderr。stdin 写完立即关闭发 EOF。
     *
     * 86MB debug APK 传输+校验可能耗时,超时放宽到 180s。
     */
    suspend fun installApkStreamed(apkFile: java.io.File, commandId: String = "ota-install"): ExecutionResult =
        runCatching {
            val started = System.currentTimeMillis()
            val binder = Shizuku.getBinder() ?: error("Shizuku binder null")
            val service = IShizukuService.Stub.asInterface(binder)
            val size = apkFile.length()

            // Step 1: 创建安装 session,解析 sessionId
            val createOut = execSimple(service, arrayOf("pm", "install-create", "-r"))
            val sessionId = parseSessionId(createOut)
                ?: return@runCatching fail(commandId, "install-create failed: $createOut", System.currentTimeMillis() - started)
            Log.i(TAG, "ota install session=$sessionId size=$size")

            // Step 2: install-write 从 stdin 写入 APK 字节
            val writeOk = execInstallWriteStream(service, sessionId, size, apkFile)
            if (!writeOk) {
                runCatching { execSimple(service, arrayOf("pm", "install-abandon", sessionId.toString())) }
                return@runCatching fail(commandId, "install-write failed session=$sessionId", System.currentTimeMillis() - started)
            }

            // Step 3: 提交安装
            val commitOut = execSimple(service, arrayOf("pm", "install-commit", sessionId.toString()))
            val duration = System.currentTimeMillis() - started
            val success = commitOut.startsWith("Success")
            if (success) ok(commandId, "installed size=$size session=$sessionId $commitOut", duration)
            else fail(commandId, "install-commit failed: $commitOut", duration)
        }.getOrElse {
            fail(commandId, "exception=${it.message}", 0L)
        }

    /** 执行一条不需要 stdin 的简单 pm 命令,返回 stdout(含 stderr 合并)。 */
    private suspend fun execSimple(service: IShizukuService, cmd: Array<String>): String =
        withTimeoutOrNull(30_000L) {
            coroutineScope {
                val process = service.newProcess(cmd, null, null)
                val outPfd: ParcelFileDescriptor? = runCatching { process.inputStream }.getOrNull()
                val errPfd: ParcelFileDescriptor? = runCatching { process.errorStream }.getOrNull()
                try {
                    val out = async(Dispatchers.IO) { readPfdFully(outPfd) }
                    val err = async(Dispatchers.IO) { readPfdFully(errPfd) }
                    val code = async(Dispatchers.IO) { process.waitFor() }
                    val outV = out.await()
                    val errV = err.await()
                    code.await()
                    (outV + errV).trim()
                } finally {
                    runCatching { outPfd?.close() }
                    runCatching { errPfd?.close() }
                    runCatching { process.destroy() }
                }
            }
        } ?: "timeout"

    /** 从 "Success: created install session [12345]" 解析 sessionId。 */
    private fun parseSessionId(output: String): Int? {
        val regex = Regex("\\[(\\d+)]")
        return regex.find(output)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 执行 `pm install-write -S <size> <id> base.apk -`,从 stdin 流式写入 APK。
     * 并发:写 stdin / 读 stdout / 读 stderr,防管道缓冲区死锁。
     */
    private suspend fun execInstallWriteStream(
        service: IShizukuService,
        sessionId: Int,
        size: Long,
        apkFile: java.io.File,
    ): Boolean = coroutineScope {
        val process = service.newProcess(
            arrayOf("pm", "install-write", "-S", size.toString(), sessionId.toString(), "base.apk", "-"),
            null, null
        )
        val stdinPfd: ParcelFileDescriptor? = runCatching { process.outputStream }.getOrNull()
        val outPfd: ParcelFileDescriptor? = runCatching { process.inputStream }.getOrNull()
        val errPfd: ParcelFileDescriptor? = runCatching { process.errorStream }.getOrNull()
        try {
            val write = async(Dispatchers.IO) {
                runCatching {
                    apkFile.inputStream().use { ins ->
                        java.io.FileOutputStream(stdinPfd!!.fileDescriptor).use { outs ->
                            ins.copyTo(outs, bufferSize = 64 * 1024)
                        }
                    }
                }
                runCatching { stdinPfd?.close() } // EOF → install-write 校验
            }
            val out = async(Dispatchers.IO) { readPfdFully(outPfd) }
            val err = async(Dispatchers.IO) { readPfdFully(errPfd) }
            val code = async(Dispatchers.IO) { process.waitFor() }

            withTimeoutOrNull(180_000L) {
                val writeErr = write.await()
                val outV = out.await()
                val errV = err.await()
                val codeV = code.await()
                if (writeErr.isFailure) {
                    Log.e(TAG, "install-write stdin failed: ${writeErr.exceptionOrNull()?.message}")
                    false
                } else if (codeV == 0 && outV.contains("Success")) {
                    true
                } else {
                    Log.e(TAG, "install-write exit=$codeV out=${outV.trim()} err=${errV.trim()}")
                    false
                }
            } ?: run {
                runCatching { process.destroy() }
                false
            }
        } finally {
            runCatching { stdinPfd?.close() }
            runCatching { outPfd?.close() }
            runCatching { errPfd?.close() }
            runCatching { process.destroy() }
        }
    }

    private suspend fun runShell(cmd: Array<String>, commandId: String, started: Long): ExecutionResult {
        return runCatching {
            val binder = Shizuku.getBinder() ?: error("Shizuku binder null")
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(cmd, null, null)
            // 取一次 stdin/stderr 的 ParcelFileDescriptor,在 finally 内统一关闭,
            // 避免 inputStream getter 多次调用产生不同 PFD 实例 + readPfd 仅关 FileInputStream 不关 pfd 的 fd 泄漏
            val outPfd: ParcelFileDescriptor? = runCatching { process.inputStream }.getOrNull()
            val errPfd: ParcelFileDescriptor? = runCatching { process.errorStream }.getOrNull()
            try {
                // C4: 原实现 waitFor/readText 同步阻塞无超时,慢命令会卡死执行器协程。
                // 改用并发 async 读 + withTimeoutOrNull(15s),超时 destroyForcibly 并返回 timeout。
                coroutineScope {
                    val out = async(Dispatchers.IO) { readPfd(outPfd) }
                    val err = async(Dispatchers.IO) { readPfd(errPfd) }
                    val code = async(Dispatchers.IO) { process.waitFor() }
                    withTimeoutOrNull(15_000L) {
                        val outV = out.await()
                        val errV = err.await()
                        val codeV = code.await()
                        val duration = System.currentTimeMillis() - started
                        if (codeV == 0) ok(commandId, outV.trim().ifEmpty { "OK" }, duration)
                        else fail(commandId, "exit=$codeV stderr=${errV.trim()}", duration)
                    } ?: run {
                        // IRemoteProcess 只有 destroy()(AIDL),没有 destroyForcibly()(java.lang.Process)
                        runCatching { process.destroy() }
                        fail(commandId, "timeout", System.currentTimeMillis() - started)
                    }
                }
            } finally {
                runCatching { outPfd?.close() }
                runCatching { errPfd?.close() }
                runCatching { process.destroy() }
            }
        }.getOrElse {
            fail(commandId, "exception=${it.message}", System.currentTimeMillis() - started)
        }
    }

    private fun readPfd(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return ""
        return runCatching {
            FileInputStream(pfd.fileDescriptor).use { it.bufferedReader().readText() }
        }.getOrElse { "" }
    }

    /**
     * 完整读取 PFD 到 EOF,返回全部文本。
     * install 场景 pm 输出可能超过 readText() 内部缓冲区,改用循环 read 确保不丢内容。
     */
    private fun readPfdFully(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return ""
        return runCatching {
            val sb = StringBuilder()
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                }
            }
            sb.toString()
        }.getOrElse { "" }
    }

    companion object {
        private const val TAG = "ShizukuExecutor"
    }
}
