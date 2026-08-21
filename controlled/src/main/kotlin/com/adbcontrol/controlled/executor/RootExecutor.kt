package com.adbcontrol.controlled.executor

import android.util.Log
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.ExecutionResult
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root 增强执行器。README 3.3 L2。
 *
 * - 设备已 root 时用 `ProcessBuilder("su", "-c", shell)` 执行(shell 作为单独 argv 传入,
 *   不再用单引号包裹,避免 shell 内含 `'` 越权执行)
 * - 输出在独立线程读取,配合 [waitFor] 超时,避免 su 卡密码提示时 readText 永久阻塞
 * - 检测 root:尝试执行 `su -c id`,成功即认为可用(缓存结果)
 */
@Singleton
class RootExecutor @Inject constructor() : CommandExecutor {

    override val name: String = "Root"

    @Volatile private var rootChecked: Boolean? = null

    override fun isAvailable(): Boolean {
        rootChecked?.let { return it }
        val ok = runCatching { runRoot("id", 3_000).success }.getOrElse { false }
        rootChecked = ok
        return ok
    }

    override fun supports(command: Command): Boolean =
        CommandShellBuilder.isShellExpressible(command)

    override suspend fun execute(command: Command, commandId: String): ExecutionResult {
        if (!isAvailable()) return fail(commandId, "ROOT_UNAVAILABLE")
        val started = System.currentTimeMillis()
        val shell = CommandShellBuilder.build(command) ?: return noPath(commandId)
        val res = runCatching { runRoot(shell, 15_000) }
            .getOrElse { ExecutionResult(commandId, false, "exception=${it.message}", System.currentTimeMillis() - started) }
        return res.copy(commandId = commandId)
    }

    /**
     * 执行一条 root shell 命令。stdout 在独立线程读取,与 waitFor 并行,
     * 任一超时则强制销毁进程,避免 su 卡密码提示时 readText 永久阻塞。
     */
    private fun runRoot(shell: String, timeoutMs: Long): ExecutionResult {
        val started = System.currentTimeMillis()
        val process = ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start()
        val outputBuf = StringBuilder()
        val inputStream = process.inputStream
        val readerThread = Thread {
            try {
                inputStream.bufferedReader().use { r ->
                    val buf = CharArray(2048)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        outputBuf.append(buf, 0, n)
                    }
                }
            } catch (e: Exception) { /* 进程销毁后流关闭属正常 */ }
        }.apply { isDaemon = true; start() }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
        if (!finished) {
            runCatching { inputStream.close() }
            runCatching { process.destroyForcibly() }
            readerThread.interrupt()
            Log.w("RootExecutor", "process timeout: $shell")
        }
        readerThread.join(500)
        val duration = System.currentTimeMillis() - started
        val out = outputBuf.toString().trim()
        return if (finished && process.exitValue() == 0) {
            ExecutionResult(commandId = "", success = true, output = out.ifEmpty { "OK" }, durationMs = duration)
        } else {
            ExecutionResult(commandId = "", success = false, output = "exit=${if (finished) process.exitValue().toString() else "timeout"} out=$out", durationMs = duration)
        }
    }
}
