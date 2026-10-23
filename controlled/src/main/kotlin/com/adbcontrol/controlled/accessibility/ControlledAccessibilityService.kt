package com.adbcontrol.controlled.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.adbcontrol.controlled.apptime.AppTimeController
import com.adbcontrol.controlled.telemetry.TelemetryEngine
import com.adbcontrol.controlled.ui.intercept.InterceptActivity
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
    @Inject lateinit var appTimeController: AppTimeController

    /** 拦截页防抖:同一包 [INTERCEPT_DEBOUNCE_MS] 内只拉起一次 */
    @Volatile private var lastInterceptPkg: String? = null
    @Volatile private var lastInterceptAt: Long = 0L

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
                runCatching { maybeIntercept(pkg, eventClass) }
                    .onFailure { Log.w(TAG, "intercept check failed", it) }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // 忽略通知事件
            }
        }
    }

    /**
     * v2.0 拦截页:用户打开受限 App 时,展示友好的全屏页(解封时间 / 剩余额度 / 申请延时)。
     *
     * 两种入口:
     * 1. 受限 App 本身到前台(suspend 未成功,如无 Shizuku)→ 直接拦截该包
     * 2. 系统"应用已暂停"对话框(package=android,SuspendedAppActivity)→ 在对话框文字中匹配受限 App 名称
     *
     * 无障碍服务被系统绑定,属后台启动 Activity 豁免;MIUI/HyperOS 仍需"后台弹出界面"权限(权限自检向导引导)。
     */
    private fun maybeIntercept(pkg: String, eventClass: String?) {
        val target: String? = when {
            appTimeController.isRestricted(pkg) -> pkg
            pkg == "android" && (eventClass?.contains("Suspend", ignoreCase = true) == true ||
                eventClass?.contains("AlertDialog", ignoreCase = true) == true) -> findSuspendedFromDialog()
            else -> null
        }
        if (target == null) return
        val now = System.currentTimeMillis()
        if (target == lastInterceptPkg && now - lastInterceptAt < INTERCEPT_DEBOUNCE_MS) return
        lastInterceptPkg = target
        lastInterceptAt = now
        Log.i(TAG, "intercept restricted app $target")
        if (pkg == "android") {
            // 关掉系统暂停对话框,再展示我们的页面
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        }
        InterceptActivity.launch(this, target)
    }

    /** 在当前窗口文字里查找受限 App 的名称(系统暂停对话框标题含 App 名)。 */
    private fun findSuspendedFromDialog(): String? {
        val restricted = appTimeController.restrictedPackages()
        if (restricted.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        for (p in restricted) {
            val label = appTimeController.labelOf(p)
            if (label.isBlank() || label == p) continue
            if (root.findAccessibilityNodeInfosByText(label).isNotEmpty()) return p
        }
        return null
    }

    override fun onInterrupt() {
        // onInterrupt 仅表示辅助反馈被打断（如通知弹窗或TTS打断），Service 连接依然存活，严禁解绑 Bridge！
        // 只有在 onDestroy() 或 onUnbind() 时才应注销 Bridge。
        Log.w(TAG, "onInterrupt: accessibility feedback interrupted")
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

    companion object {
        private const val TAG = "ControlledA11y"
        private const val INTERCEPT_DEBOUNCE_MS = 2_500L
    }
}
