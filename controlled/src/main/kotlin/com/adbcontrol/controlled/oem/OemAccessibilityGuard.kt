package com.adbcontrol.controlled.oem

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.adbcontrol.controlled.accessibility.AccessibilityServiceBridge

/**
 * AccessibilityService 健康守护。README 第七章 + MIUI 11+ 专项。
 *
 * 背景:MIUI 11+ 会在用户长时间无操作(约 7 天)后自动关闭无障碍服务,
 * 导致 [com.adbcontrol.controlled.executor.AccessibilityExecutor] 失去 L3 兼容能力。
 * 本类提供"是否还连着"的检测,在 ControlledService 启动时与 HeartbeatGuardWorker
 * 周期(15 分钟)中调用,被关闭时日志告警 + 上报主控端(由健康上报通道消费)。
 *
 * 注意:普通应用无法静默重新启用无障碍服务,只能引导用户去系统设置打开。
 */
object OemAccessibilityGuard {

    private const val TAG = "OemA11yGuard"

    /**
     * 综合判活:
     * 1. 本进程内运行实例仍绑定([AccessibilityServiceBridge.isConnected])
     * 2. 系统侧启用名单([Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES])仍包含本服务
     *
     * 任一为 false 即视为掉线(MIUI 自动关闭、系统重启后未自启、用户手动关闭均会触发)。
     *
     * @param serviceClass 全限定类名,默认指向本模块的 ControlledAccessibilityService
     */
    fun isConnected(
        context: Context,
        serviceClass: String =
            "com.adbcontrol.controlled.accessibility.ControlledAccessibilityService",
    ): Boolean {
        if (!AccessibilityServiceBridge.isConnected()) return false
        return isServiceEnabled(context, serviceClass)
    }

    /**
     * 仅查系统侧启用名单,不查运行实例。
     * flat 字符串形如 "pkg/cls1:pkg/cls2",[ComponentName.flattenToString] 可解析。
     */
    fun isServiceEnabled(
        context: Context,
        serviceClass: String =
            "com.adbcontrol.controlled.accessibility.ControlledAccessibilityService",
    ): Boolean = runCatching {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return@runCatching false
        val expected = ComponentName(context.packageName, serviceClass).flattenToString()
        flat.split(":").any { it == expected }
    }.getOrDefault(false)

    /**
     * 启动时与周期检查入口。返回 true=正常,false=掉线需引导用户重开。
     * 仅日志,不弹通知(避免与前台服务通知串扰);后续可由健康上报消费。
     */
    fun checkAndLog(
        context: Context,
        serviceClass: String =
            "com.adbcontrol.controlled.accessibility.ControlledAccessibilityService",
    ): Boolean {
        val ok = isConnected(context, serviceClass)
        if (ok) {
            Log.d(TAG, "accessibility still connected")
        } else {
            // MIUI 11+ 自动关 / 用户手动关 / 系统重启未自启,均落到这里
            val hint = if (OemHelper.isMiui())
                "MIUI may have auto-disabled accessibility (7d inactivity) — user re-enable required"
            else "accessibility dropped — user re-enable required"
            Log.w(TAG, hint)
        }
        return ok
    }
}
