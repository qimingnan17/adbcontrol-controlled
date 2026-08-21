package com.adbcontrol.controlled.oem

import android.app.NotificationChannel
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.adbcontrol.controlled.accessibility.AccessibilityServiceBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MIUI 专项适配。README 3.1 B8 / 第六章 / 第七章。
 *
 * 覆盖任务 1~7 的 MIUI 专项点:
 * - USB 调试(安全设置)检测:MIUI 13+ Shizuku 通过无线调试启动时,该开关未开则 input 命令无效
 * - 应用锁状态检测:MIUI 应用锁可能拦截 force-stop / pm install
 * - 受保护应用(神隐模式)名单:电池白名单还不够,需额外引导用户加入
 * - 通知渠道专项:IMPORTANCE_LOW + setShowBadge(false) + setBypassDnd(true),避免 setSound(Uri) 被归到打扰
 * - 替代锁屏广播:MIUI 上 DeviceAdmin lockNow() 行为略不同,提供已知 MIUI 锁屏广播兜底
 * - 无障碍健康守护:MIUI 11+ 会 7 天后自动关无障碍,委托 [OemAccessibilityGuard]
 *
 * 全部检测为启发式:MIUI 不公开 API,只能读 Settings.Secure / Global 与 SystemProperties。
 */
@Singleton
class MiuiAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // ---------- 版本 ----------

    fun isMiui(): Boolean = OemHelper.isMiui()

    /** MIUI 大版本 >= [version](如 11、12、13、14)。非 MIUI 永远返回 false。 */
    fun isMiuiVersionGE(version: Int): Boolean = OemHelper.isMiuiVersionGE(version)

    // ---------- USB 调试(安全设置)检测(任务 4) ----------

    /**
     * MIUI 13+ Shizuku 通过无线调试启动时,"USB 调试(安全设置)"开关必须打开,
     * 否则 Shizuku 看似已授权但 `input` / `pm` 等命令无效。
     *
     * 该开关存在 MIUI 私有 Settings key,普通应用读不到准确值,这里做启发式判断:
     * 读不到默认返回 true(不阻塞,让用户自己遇到失败时再排查)。
     */
    fun isUsbSecureDebugEnabled(): Boolean {
        if (!isMiui()) return true
        return runCatching {
            // 已知 key:adb_secure / adb_install / adb_secure_settings;不同 MIUI 版本 key 名不一致
            val v = Settings.Global.getInt(context.contentResolver, "adb_secure_settings", 1)
            v == 1
        }.getOrDefault(true)
    }

    /** MIUI 上 Shizuku 检测失败时的引导文案(供 HealthReporter / UI 消费)。非 MIUI 返回 null。 */
    fun miuiShizukuHint(): String? {
        if (!isMiui()) return null
        if (isUsbSecureDebugEnabled()) return null
        return "MIUI 设置 → 我的设备 → 全部参数 → MIUI 版本(连续点击)→ 开发者选项 → " +
            "开启「USB 调试(安全设置)」后,Shizuku 的 input 命令才能在 MIUI 上生效。"
    }

    // ---------- 应用锁检测(任务 3) ----------

    /**
     * MIUI 应用锁状态检测(启发式)。系统私有 key,普通应用无权读取准确列表,
     * 这里仅做"应用锁功能总开关是否开启"的兜底判断,读不到返回 false(假设未上锁)。
     */
    fun isAppLockLikelyEnabled(): Boolean {
        if (!isMiui()) return false
        return runCatching {
            Settings.Secure.getString(context.contentResolver, "app_lock_enabled")
                ?.toIntOrNull() == 1
        }.getOrDefault(false)
    }

    // ---------- 受保护应用 / 神隐模式(任务 6) ----------

    /**
     * 是否已加入"受保护应用"名单。MIUI 不公开此列表,无法直接读;
     * 这里返回 true(假设已加)以免阻塞健康上报,真正未加的状态需由 UI 引导后用户确认。
     */
    fun isProtectedAppLikely(): Boolean {
        if (!isMiui()) return true
        return true
    }

    // ---------- 通知渠道专项(任务 2) ----------

    /**
     * 在 MIUI 11+ 上对前台通知渠道做专项配置:
     * - [NotificationChannel.setShowBadge](false):不显示角标
     * - [NotificationChannel.setBypassDnd](true):MIUI 通知重要性独立判断,绕过 DND 保证常驻显形
     * - 不调用 [NotificationChannel.setSound]:部分 MIUI 版本会因 setSound(Uri) 强制归到"打扰"分类,
     *   与本应用 IMPORTANCE_LOW 无声常驻通知的初衷冲突
     *
     * 非 MIUI 设备仅做 setShowBadge(false) 通用配置,不强行 setBypassDnd / setSound(null),
     * 避免影响其他 ROM 的标准行为。
     */
    fun applyForegroundChannel(channel: NotificationChannel) {
        channel.setShowBadge(false)
        if (isMiui()) {
            runCatching { channel.setBypassDnd(true) }
            // Bug 8:HyperOS 1.0/2.0 部分版本禁止 setSound(null, null) 直接抛 IllegalArgumentException,
            // 导致 onCreate 崩溃,ControlledService 无法启动。与 setBypassDnd 一样包 runCatching。
            runCatching { channel.setSound(null, null) }
        }
    }

    // ---------- 替代锁屏广播(任务 7) ----------

    /**
     * MIUI 上 [android.app.admin.DevicePolicyManager.lockNow] 行为略不同(可能延迟或弹锁屏组件),
     * 提供 MIUI 替代方案:发送已知 MIUI 锁屏广播,失败回退 false 由调用方走
     * [android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN] 或 dpm.lockNow。
     *
     * 广播 action 取自社区已知清单,逐个尝试,首个成功即返回 true(无可靠反馈,故仅判断未抛异常)。
     */
    fun lockScreenViaBroadcast(): Boolean {
        if (!isMiui()) return false
        val actions = listOf(
            "miui.intent.action.LOCK_SCREEN",
            "com.miui.action.LockScreen",
        )
        for (action in actions) {
            val ok = runCatching {
                context.sendBroadcast(Intent(action))
                true
            }.getOrElse {
                Log.v(TAG, "lock broadcast $action failed: ${it.message}")
                false
            }
            if (ok) {
                Log.i(TAG, "lockScreenViaBroadcast via $action")
                return true
            }
        }
        return false
    }

    // ---------- 无障碍健康(任务 5) ----------

    /**
     * MIUI 11+ 7 天后自动关无障碍,提供"还连着吗"检测。委托 [OemAccessibilityGuard],
     * 综合"运行实例 + 系统启用名单"两个信号。
     */
    fun isAccessibilityStillConnected(
        serviceClass: String =
            "com.adbcontrol.controlled.accessibility.ControlledAccessibilityService",
    ): Boolean = AccessibilityServiceBridge.isConnected() &&
        OemAccessibilityGuard.isServiceEnabled(context, serviceClass)

    companion object { private const val TAG = "MiuiAdapter" }
}
