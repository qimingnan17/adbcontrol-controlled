package com.adbcontrol.controlled.ui.wizard

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.adbcontrol.controlled.accessibility.ControlledAccessibilityService
import com.adbcontrol.controlled.admin.ControlledDeviceAdminReceiver
import com.adbcontrol.controlled.oem.OemHelper
import rikka.shizuku.Shizuku

/**
 * v2.0 权限自检。逐项检测被控端所需的系统授权,并给出"一键修复"跳转。
 *
 * MIUI/HyperOS 的"自启动"与"后台弹出界面"不是标准权限:通过 MIUI 私有 AppOps 码(10008 / 10021)
 * 反射读取,读不到则标为 UNKNOWN(需用户手动确认,确认结果本地记住)。
 */
object PermissionChecker {

    enum class Id {
        SHIZUKU, ACCESSIBILITY, DEVICE_ADMIN, NOTIFICATION, LOCATION, LOCATION_BACKGROUND,
        USAGE_ACCESS, BATTERY_OPTIMIZATION, MIUI_AUTOSTART, MIUI_BG_POPUP,
    }

    enum class Status { GRANTED, MISSING, UNKNOWN }

    data class Item(
        val id: Id,
        val title: String,
        val description: String,
        val required: Boolean,
        val status: Status,
    ) {
        val ok: Boolean get() = status == Status.GRANTED
    }

    private const val PREFS = "permission_wizard"
    private const val KEY_DONE = "wizard_done"
    private const val KEY_CONFIRMED_PREFIX = "confirmed_"

    /** MIUI 私有 AppOps:自启动 / 后台弹出界面 */
    private const val MIUI_OP_AUTO_START = 10008
    private const val MIUI_OP_BACKGROUND_START_ACTIVITY = 10021

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    fun isWizardDone(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markWizardDone(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** 用户手动确认已在系统页完成设置(无法自动检测的 MIUI 项)。 */
    fun confirmManually(ctx: Context, id: Id) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONFIRMED_PREFIX + id.name, true).apply()
    }

    private fun isConfirmed(ctx: Context, id: Id): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONFIRMED_PREFIX + id.name, false)

    fun isMiuiFamily(): Boolean = OemHelper.isMiui() || OemHelper.oem == OemHelper.Oem.XIAOMI

    fun check(ctx: Context): List<Item> = buildList {
        add(Item(Id.SHIZUKU, "Shizuku 授权", "静默执行管控命令(暂停应用、静默更新等)的主通道", true, shizukuStatus(ctx)))
        add(Item(Id.ACCESSIBILITY, "无障碍服务", "识别前台应用、展示受限提示页", true, bool(isAccessibilityOn(ctx))))
        add(Item(Id.DEVICE_ADMIN, "设备管理器", "远程锁屏、防止被随意卸载", false, bool(isDeviceAdmin(ctx))))
        add(Item(Id.NOTIFICATION, "通知权限", "显示常驻状态、提醒与审批结果", true, bool(isNotificationOn(ctx))))
        add(Item(Id.LOCATION, "定位权限", "位置上报与地理围栏", false, bool(hasFineLocation(ctx))))
        add(Item(Id.LOCATION_BACKGROUND, "后台定位(始终允许)", "息屏 / 后台时仍可定位", false, bool(hasBackgroundLocation(ctx))))
        add(Item(Id.USAGE_ACCESS, "使用情况访问", "统计应用使用时长、执行时长限制", true, bool(hasUsageAccess(ctx))))
        add(Item(Id.BATTERY_OPTIMIZATION, "忽略电池优化", "防止系统休眠时断开连接", true, bool(isIgnoringBattery(ctx))))
        if (isMiuiFamily()) {
            add(Item(Id.MIUI_AUTOSTART, "自启动(MIUI/HyperOS)", "开机及被清理后自动恢复", true,
                miuiOp(ctx, MIUI_OP_AUTO_START, Id.MIUI_AUTOSTART)))
            add(Item(Id.MIUI_BG_POPUP, "后台弹出界面(MIUI/HyperOS)", "受限时弹出提示页", true,
                miuiOp(ctx, MIUI_OP_BACKGROUND_START_ACTIVITY, Id.MIUI_BG_POPUP)))
        }
    }

    /** 必需项是否全部就绪(UNKNOWN 不算缺失,避免误报)。 */
    fun allRequiredOk(items: List<Item>): Boolean = items.none { it.required && it.status == Status.MISSING }

    private fun bool(b: Boolean) = if (b) Status.GRANTED else Status.MISSING

    // ---------- 检测 ----------

    fun shizukuStatus(ctx: Context): Status = runCatching {
        if (!Shizuku.pingBinder()) return@runCatching Status.MISSING
        if (Shizuku.isPreV11()) return@runCatching Status.MISSING
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Status.GRANTED else Status.MISSING
    }.getOrDefault(Status.MISSING)

    fun isShizukuInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true }.getOrDefault(false)

    fun isAccessibilityOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains(ControlledAccessibilityService::class.java.simpleName)
    }

    fun isDeviceAdmin(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(ctx, ControlledDeviceAdminReceiver::class.java))
    }

    fun isNotificationOn(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val runtime = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtime && nm.areNotificationsEnabled()
    }

    fun hasFineLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun isIgnoringBattery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 反射 AppOpsManager.checkOpNoThrow(int, int, String) 读 MIUI 私有 op。 */
    private fun miuiOp(ctx: Context, op: Int, id: Id): Status {
        val mode = runCatching {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            m.invoke(appOps, op, Process.myUid(), ctx.packageName) as Int
        }.getOrNull()
        return when {
            mode == AppOpsManager.MODE_ALLOWED -> Status.GRANTED
            isConfirmed(ctx, id) -> Status.GRANTED
            mode == null -> Status.UNKNOWN
            else -> Status.MISSING
        }
    }

    // ---------- 修复跳转 ----------

    /** Shizuku:已安装→打开 Shizuku / 请求授权;未安装→应用商店,失败回退官网。 */
    fun fixShizuku(ctx: Context) {
        runCatching {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
                return
            }
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (launch != null) { start(ctx, launch); return }
        if (!start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG")))) {
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/zh-hans/download/")))
        }
    }

    fun accessibilityIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(
                "android.intent.extra.COMPONENT_NAME",
                ComponentName(ctx, ControlledAccessibilityService::class.java).flattenToString(),
            )
        }

    fun deviceAdminIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, ControlledDeviceAdminReceiver::class.java))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "激活设备管理以支持远程锁屏、防止误卸载")
        }

    fun notificationSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    fun usageAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:${ctx.packageName}"))

    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun appDetailsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

    /** MIUI 自启动管理:安全中心 → 自启动,多级回退。 */
    fun fixMiuiAutostart(ctx: Context) {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity")),
            appDetailsIntent(ctx),
        )
        startFirst(ctx, candidates)
    }

    /** MIUI 后台弹出界面:应用权限编辑页(HyperOS / MIUI 12+ 两种组件),多级回退。 */
    fun fixMiuiBgPopup(ctx: Context) {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
                .putExtra("extra_pkgname", ctx.packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"))
                .putExtra("extra_pkgname", ctx.packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", ctx.packageName),
            appDetailsIntent(ctx),
        )
        startFirst(ctx, candidates)
    }

    fun startFirst(ctx: Context, intents: List<Intent>): Boolean {
        for (i in intents) if (start(ctx, i)) return true
        return false
    }

    fun start(ctx: Context, intent: Intent): Boolean = runCatching {
        if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    }.getOrElse {
        Log.w("PermissionChecker", "start ${intent.component ?: intent.action} failed: ${it.message}")
        false
    }

    const val REQUEST_CODE_SHIZUKU = 4242
}
