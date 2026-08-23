package com.adbcontrol.controlled.executor

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.accessibilityservice.AccessibilityService
import com.adbcontrol.controlled.accessibility.AccessibilityServiceBridge
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.model.ExecutionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 无障碍兼容执行器。README 3.3 L3。
 *
 * 无 root 无 Shizuku 时作为兜底,提供:
 * - 手势分派(点击/滑动)
 * - 截屏(API 30+ takeScreenshot)
 * - 全局动作(锁屏/返回/HOME)
 * - UI 控件拦截(屏蔽指定入口)
 */
@Singleton
class AccessibilityExecutor @Inject constructor() : CommandExecutor {

    override val name: String = "Accessibility"

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun isAvailable(): Boolean = AccessibilityServiceBridge.isConnected()

    override fun supports(command: Command): Boolean = when (command.category) {
        CommandCategory.INPUT -> true
        CommandCategory.SYSTEM -> command.action == "lockScreen" || command.action == "goHome" || command.action == "back" || command.action == "recents"
        CommandCategory.APP -> command.action == "screencap" || command.action == "blockUi"
        else -> false
    }

    override suspend fun execute(command: Command, commandId: String): ExecutionResult {
        if (!isAvailable()) return fail(commandId, "ACCESSIBILITY_UNAVAILABLE")
        val started = System.currentTimeMillis()
        return runCatching {
            when (command.category) {
                CommandCategory.INPUT -> executeInput(command, commandId, started)
                CommandCategory.SYSTEM -> executeSystem(command, commandId, started)
                CommandCategory.APP -> executeApp(command, commandId, started)
                else -> noPath(commandId)
            }
        }.getOrElse {
            fail(commandId, "exception=${it.message}", System.currentTimeMillis() - started)
        }
    }

    private fun executeInput(c: Command, commandId: String, started: Long): ExecutionResult {
        val ok = when (c.action) {
            "tap" -> {
                val x = c.params["x"]?.toFloatOrNull() ?: return fail(commandId, "missing x")
                val y = c.params["y"]?.toFloatOrNull() ?: return fail(commandId, "missing y")
                AccessibilityServiceBridge.dispatchTap(x, y)
            }
            "swipe" -> {
                val x1 = c.params["x1"]?.toFloatOrNull() ?: return fail(commandId, "missing x1")
                val y1 = c.params["y1"]?.toFloatOrNull() ?: return fail(commandId, "missing y1")
                val x2 = c.params["x2"]?.toFloatOrNull() ?: return fail(commandId, "missing x2")
                val y2 = c.params["y2"]?.toFloatOrNull() ?: return fail(commandId, "missing y2")
                val ms = c.params["durationMs"]?.toLongOrNull() ?: 300
                AccessibilityServiceBridge.dispatchSwipe(x1, y1, x2, y2, ms)
            }
            "keyevent" -> {
                val code = c.params["code"]?.toIntOrNull() ?: return fail(commandId, "missing code")
                val action = globalActionFor(code)
                if (action < 0) return fail(commandId, "unsupported keycode", System.currentTimeMillis() - started)
                AccessibilityServiceBridge.performGlobalAction(action)
            }
            "text" -> false // 无障碍输入文本需通过 InputMethod 子类,留 TODO
            else -> false
        }
        return if (ok) ok(commandId, "OK", System.currentTimeMillis() - started)
        else fail(commandId, "gesture dispatch failed", System.currentTimeMillis() - started)
    }

    private fun globalActionFor(keycode: Int): Int = when (keycode) {
        4 -> AccessibilityService.GLOBAL_ACTION_BACK // KEYCODE_BACK
        3 -> AccessibilityService.GLOBAL_ACTION_HOME // KEYCODE_HOME
        26 -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN // KEYCODE_POWER
        187 -> AccessibilityService.GLOBAL_ACTION_RECENTS // KEYCODE_APP_SWITCH
        220 -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN // KEYCODE_SLEEP
        else -> -1 // 未知 keycode 不再默认 BACK,避免意外退出当前应用
    }

    private fun executeSystem(c: Command, commandId: String, started: Long): ExecutionResult {
        val ok = when (c.action) {
            "lockScreen" -> AccessibilityServiceBridge.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "goHome" -> AccessibilityServiceBridge.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> AccessibilityServiceBridge.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "recents" -> AccessibilityServiceBridge.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> return noPath(commandId)
        }
        return if (ok) ok(commandId, "OK", System.currentTimeMillis() - started)
        else fail(commandId, "global action failed", System.currentTimeMillis() - started)
    }

    private suspend fun executeApp(c: Command, commandId: String, started: Long): ExecutionResult {
        return when (c.action) {
            "screencap" -> takeScreenshot(commandId, started)
            "blockUi" -> {
                val text = c.params["text"] ?: return fail(commandId, "missing text")
                val ok = AccessibilityServiceBridge.clickByText(text)
                return if (ok) ok(commandId, "blocked", System.currentTimeMillis() - started)
                else fail(commandId, "node not found", System.currentTimeMillis() - started)
            }
            else -> noPath(commandId)
        }
    }

    /** README 7:AccessibilityService.takeScreenshot (API 30+)。返回压缩后 PNG 字节数据。 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenPng(): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val service = AccessibilityServiceBridge.get() ?: run {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    // 协程已取消/已 resume 时直接退出,避免 IllegalStateException(Already resumed)
                    if (!cont.isActive) return
                    runCatching {
                        var bitmap: Bitmap? = null
                        var soft: Bitmap? = null
                        try {
                            bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            val b = bitmap // val 快照,确保 smart cast 在 copy() 调用处可用
                            soft = if (b != null) try {
                                b.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (oom: OutOfMemoryError) {
                                null
                            } else null
                            val png = if (soft != null) ByteArrayOutputStream().use { baos ->
                                soft!!.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                baos.toByteArray()
                            } else null
                            if (png != null && cont.isActive) {
                                cont.resume(png)
                            } else if (cont.isActive) {
                                cont.resume(null)
                            }
                        } finally {
                            runCatching { soft?.recycle() }
                            runCatching { bitmap?.recycle() }
                            runCatching { screenshot.hardwareBuffer.close() }
                        }
                    }.onFailure { t ->
                        // runCatching 内未 resume 兜底,避免协程悬挂
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (!cont.isActive) return
                    Log.w(TAG, "takeScreenshot failed code=$errorCode")
                    runCatching { cont.resume(null) }
                }
            }
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, screenshotExecutor, callback)
        }

    /** README 7:AccessibilityService.takeScreenshot (API 30+)。返回压缩后 PNG 字节数据。 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(commandId: String, started: Long): ExecutionResult {
        val png = captureScreenPng()
        val duration = System.currentTimeMillis() - started
        return if (png != null) {
            ok(commandId, "screenshot bytes=${png.size}", duration)
        } else {
            fail(commandId, "bitmap conversion failed / service null", duration)
        }
    }

    companion object {
        private const val TAG = "AccessibilityExecutor"
    }
}
