package com.adbcontrol.controlled.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.adbcontrol.controlled.telemetry.TelemetryEngine
import com.adbcontrol.shared.model.ActivityReport.ActivityEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 被控端无障碍服务。README 第七章。
 *
 * 能力:
 * - 监听窗口切换(TYPE_WINDOW_STATE_CHANGED)→ 上报 activity/{deviceId}
 * - 模拟手势(通过 [AccessibilityServiceBridge])
 * - 截屏(API 30+ takeScreenshot,由 AccessibilityExecutor 调)
 * - UI 控件拦截(屏蔽指定入口如朋友圈)
 *
 * 防卸载:与 DeviceAdmin 配合,设备管理激活后无法在设置中禁用无障碍。
 */
@AndroidEntryPoint
class ControlledAccessibilityService : AccessibilityService() {

    @Inject lateinit var telemetryEngine: TelemetryEngine

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceBridge.bind(this)
        Log.i(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == packageName) return // 忽略自身
                val eventClass = event.className?.toString()
                Log.d(TAG, "window changed pkg=$pkg class=$eventClass")
                telemetryEngine.reportActivity(pkg, ActivityEvent.APP_FOREGROUND)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // 通知事件可作为 NotificationListener 的冗余补充
            }
        }
    }

    override fun onInterrupt() {
        // Bug 6:系统中断当前所有操作(手势/节点遍历/截屏)。MIUI 因系统动画/权限弹窗频繁触发。
        // 立即 unbind,避免后续 execute 调用已中断的服务实例 dispatchGesture/takeScreenshot 抛异常。
        // 系统恢复时会再次调用 onServiceConnected 重新 bind。
        // 注意:AccessibilityService.onInterrupt() 是 abstract,不能调用 super。
        Log.w(TAG, "onInterrupt — unbind bridge")
        runCatching { AccessibilityServiceBridge.unbind() }
    }

    override fun onDestroy() {
        // Bug 5:onUnbind 不一定被调用(系统低内存直接销毁、服务 crash 后重启),
        // 必须在 onDestroy 中也清理 Bridge,否则 AtomicReference 仍指向已销毁的服务实例,
        // AccessibilityExecutor 后续调用 dispatchGesture 会 IllegalStateException / 返回 null。
        runCatching { AccessibilityServiceBridge.unbind() }
        Log.i(TAG, "accessibility service destroyed")
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        runCatching { AccessibilityServiceBridge.unbind() }
        Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    companion object { private const val TAG = "ControlledA11y" }
}
