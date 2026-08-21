package com.adbcontrol.controlled.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 厂商后台保活跳转集合。README 3.1 B8 / L8。
 *
 * 不写双进程守护、不复活等灰色手段,仅按 [OemHelper.oem] 路由到厂商自启 / 受保护应用 /
 * 神隐模式等系统设置页,引导用户手动把本应用加入"允许后台运行 / 自启动"名单(合规适配)。
 *
 * 所有跳转均 try-catch 包裹,失败回退到应用详情页([Settings.ACTION_APPLICATION_DETAILS_SETTINGS]),
 * 仍失败则返回 false 让调用方决定后续引导文案。
 *
 * 实现是无状态单例,方法都接受 [Context] 参数,便于在 Receiver / Worker / UI 任意调用。
 */
object OemBatterySettings {

    private const val TAG = "OemBatterySettings"

    /**
     * 按当前厂商打开"自启动管理"页面,失败回退应用详情页。
     * @return 是否成功 startActivity(不代表用户已加白名单)
     */
    fun openAutoStart(context: Context): Boolean {
        val intent = autoStartIntent() ?: return openAppDetails(context)
        return launch(context, intent, fallbackToDetails = true)
    }

    /**
     * 打开厂商"受保护应用 / 后台管理"页面(MIUI 称神隐模式、Huawei 称受保护应用)。
     * 厂商无对应入口时回退应用详情。
     */
    fun openProtectedApps(context: Context): Boolean {
        val intent = protectedAppsIntent() ?: return openAppDetails(context)
        return launch(context, intent, fallbackToDetails = true)
    }

    /**
     * MIUI 专项入口聚合(自启动 + 神隐 + 应用锁)。
     * 由于一次只能展示一个页面,这里仅打开自启动页,神隐/应用锁请用 [openMiuiGodMode] /
     * [openMiuiAppLock] 单独触发。非 MIUI 设备直接回退应用详情。
     */
    fun openMiuiAll(context: Context): Boolean {
        if (!OemHelper.isMiui()) return openAppDetails(context)
        return openAutoStart(context)
    }

    /** 单独打开 MIUI 神隐模式 / 应用权限编辑页。 */
    fun openMiuiGodMode(context: Context): Boolean =
        launch(context, miuiGodModeIntent(), fallbackToDetails = true)

    /** 单独打开 MIUI 应用锁设置页。 */
    fun openMiuiAppLock(context: Context): Boolean =
        launch(context, miuiAppLockIntent(), fallbackToDetails = true)

    /** 通用兜底:打开本应用详情页(所有 ROM 都支持)。 */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        return launch(context, intent, fallbackToDetails = false)
    }

    /** 直接请求把本应用加入电池白名单(需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限,Manifest 已声明)。 */
    fun requestBatteryWhitelist(context: Context): Boolean = runCatching {
        // 部分国产 ROM 在系统层会拦截该 Intent,失败回退系统电池优化列表
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.recoverCatching {
        Log.w(TAG, "request battery whitelist failed, fallback to settings list", it)
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse { false }

    // ---------- 厂商自启 Intent 路由 ----------

    private fun autoStartIntent(): Intent? = when (OemHelper.oem) {
        OemHelper.Oem.XIAOMI -> miuiAutoStartIntent()
        OemHelper.Oem.HUAWEI -> Intent().setComponent(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalApp",
            )
        )
        OemHelper.Oem.OPPO -> Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupActivity",
            )
        )
        OemHelper.Oem.VIVO -> Intent().setComponent(
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpActivity",
            )
        )
        OemHelper.Oem.MEIZU -> Intent().setComponent(
            ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.permission.SmartBGActivity",
            )
        )
        OemHelper.Oem.SAMSUNG -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        OemHelper.Oem.OTHER -> null
    }

    private fun protectedAppsIntent(): Intent? = when (OemHelper.oem) {
        OemHelper.Oem.XIAOMI -> miuiGodModeIntent() // MIUI 神隐 ≈ 受保护应用
        OemHelper.Oem.HUAWEI -> Intent().setComponent(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            )
        )
        OemHelper.Oem.OPPO -> Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupActivity",
            )
        )
        OemHelper.Oem.VIVO -> Intent().setComponent(
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.HighBackgroundActivity",
            )
        )
        OemHelper.Oem.MEIZU -> Intent().setComponent(
            ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.powerui.PowerAppPermissionActivity",
            )
        )
        OemHelper.Oem.SAMSUNG -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        OemHelper.Oem.OTHER -> null
    }

    // ---------- MIUI 内部 Intent ----------
    // 参考 README 3.1 任务给定的 ComponentName。

    private fun miuiAutoStartIntent(): Intent = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    )

    private fun miuiGodModeIntent(): Intent = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
        )
    )

    private fun miuiAppLockIntent(): Intent = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
        )
    )

    /**
     * 启动 Intent,失败按 [fallbackToDetails] 决定是否回退应用详情页。
     * 始终补 FLAG_ACTIVITY_NEW_TASK,允许从 Application / Worker 等 non-Activity Context 启动。
     */
    private fun launch(
        context: Context,
        intent: Intent,
        fallbackToDetails: Boolean,
    ): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.recoverCatching {
            Log.w(TAG, "launch failed: ${intent.component ?: intent.action}", it)
            if (!fallbackToDetails) return@recoverCatching false
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrElse {
            Log.w(TAG, "all fallbacks failed", it)
            false
        }
    }
}
