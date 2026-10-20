package com.adbcontrol.controlled.ui.transparency

import android.content.Context
import com.adbcontrol.controlled.ui.wizard.PermissionChecker
import com.adbcontrol.controlled.ui.wizard.PermissionChecker.Status

/**
 * v2.0 透明度:"本设备受管理"——向设备使用者如实列出当前已启用的管理能力。
 * 常驻通知(展开)与首页"本设备受管理"页共用同一份列表,保证两处一致。
 */
object ManagedCapabilities {

    data class Capability(val title: String, val detail: String)

    fun list(ctx: Context): List<Capability> = buildList {
        val shizuku = PermissionChecker.shizukuStatus(ctx) == Status.GRANTED
        val a11y = PermissionChecker.isAccessibilityOn(ctx)
        val usage = PermissionChecker.hasUsageAccess(ctx)
        val location = PermissionChecker.hasFineLocation(ctx)
        val bgLocation = PermissionChecker.hasBackgroundLocation(ctx)
        val admin = PermissionChecker.isDeviceAdmin(ctx)

        add(Capability("设备状态上报", "电量、网络、亮屏状态、当前前台应用"))
        if (usage) add(Capability("应用使用时长统计", "每天各应用的使用分钟数(约每 15 分钟同步一次)"))
        if (usage || shizuku) add(Capability("应用时间管理", "按家长设置的时长上限 / 禁用时段暂停应用,可申请延时"))
        if (location) add(Capability(
            "位置上报",
            (if (bgLocation) "含后台定位" else "仅前台定位") + ",支持地理围栏进出提醒;低电量或静止时降低频率",
        ))
        if (a11y) add(Capability("无障碍服务", "识别前台应用、展示受限提示页、远程点击/滑动输入"))
        if (shizuku) add(Capability("远程命令(Shizuku)", "安装/卸载/暂停应用、截屏、静默更新本应用等"))
        if (admin) add(Capability("设备管理器", "远程锁屏、防止随意卸载"))
        add(Capability("远程提醒通知", "家长发送的提醒与定时任务通知"))
        add(Capability("自动更新", "本应用的新版本会自动下载并校验完整性(SHA-256)后安装"))
    }

    /** 常驻通知的单行摘要。 */
    fun summary(ctx: Context): String {
        val l = list(ctx)
        return "已启用 ${l.size} 项管理能力:" + l.take(4).joinToString("、") { it.title } + if (l.size > 4) " 等" else ""
    }

    /** 常驻通知展开后的多行列表。 */
    fun bigText(ctx: Context): String = list(ctx).joinToString("\n") { "• ${it.title}:${it.detail}" }
}
