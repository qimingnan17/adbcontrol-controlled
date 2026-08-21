package com.adbcontrol.controlled.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicReference

/**
 * 活跃的 [ControlledAccessibilityService] 单例桥接。
 *
 * AccessibilityService 由系统创建,无法用 Hilt 注入。通过此桥接让
 * [com.adbcontrol.controlled.executor.AccessibilityExecutor] 拿到运行实例。
 *
 * - service `onServiceConnected` 时调用 [bind]
 * - `onDestroy`/`onUnbind` 时调用 [unbind]
 */
object AccessibilityServiceBridge {

    private val ref = AtomicReference<AccessibilityService?>()

    fun bind(service: AccessibilityService) { ref.set(service) }
    fun unbind() { ref.set(null) }
    fun isConnected(): Boolean = ref.get() != null
    fun get(): AccessibilityService? = ref.get()

    /** 在 root 节点查找含文本的控件并执行点击(README 7 UI 拦截:屏蔽朋友圈入口等)。 */
    fun clickByText(text: String): Boolean {
        val service = ref.get() ?: return false
        val root = service.rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                parent = parent.parent
            }
        }
        return false
    }

    /** 模拟单次点击手势(README 3.3 输入控制兼容路径)。 */
    @RequiresApi(Build.VERSION_CODES.N)
    fun dispatchTap(x: Float, y: Float): Boolean {
        val service = ref.get() ?: return false
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.1f, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /** 模拟滑动手势。 */
    @RequiresApi(Build.VERSION_CODES.N)
    fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val service = ref.get() ?: return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /** 执行全局动作(返回 HOME/锁屏/通知栏等)。 */
    fun performGlobalAction(action: Int): Boolean {
        val service = ref.get() ?: return false
        return service.performGlobalAction(action)
    }
}
