package com.adbcontrol.controlled.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.adbcontrol.controlled.executor.ShizukuExecutor
import kotlinx.coroutines.runBlocking

/**
 * 系统无障碍快捷方式(音量长按 / 悬浮入口)开关控制。
 *
 * 系统在授予无障碍后可能自动登记 `accessibility_shortcut_target_service`,
 * 用户希望可自主开/关。写入该 secure 设置需要 WRITE_SECURE_SETTINGS(signature 级,
 * 普通应用拿不到),因此运行时走两条路:
 * 1) App 恰好持有 WRITE_SECURE_SETTINGS(如 adb 授权过)→ 直接 Settings.Secure.putString;
 * 2) 否则经 Shizuku 执行 `settings put/delete secure ...`。
 *
 * 值格式为冒号分隔的组件列表,读写都按集合处理,不破坏其他服务的快捷方式注册。
 */
class AccessibilityShortcutController(
    private val context: Context,
    private val shizukuExecutor: ShizukuExecutor,
) {

    private val targetComponent: String =
        ComponentName(context, ControlledAccessibilityService::class.java).flattenToString()

    private fun currentTargets(): MutableSet<String> {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, KEY)
        }.getOrNull().orEmpty()
        return LinkedHashSet(raw.split(":").map { it.trim() }.filter { it.isNotEmpty() })
    }

    /** 快捷方式当前是否指向本应用的无障碍服务。读取失败视为关闭。 */
    fun isEnabled(): Boolean = currentTargets().contains(targetComponent)

    /**
     * 开/关快捷方式。返回 null 表示成功,否则为用户可读的错误信息。
     * 注意:切换后系统设置页可能需要重新进入才刷新显示。
     */
    fun setEnabled(enable: Boolean): String? {
        val targets = currentTargets()
        val changed = if (enable) targets.add(targetComponent) else targets.remove(targetComponent)

        // 路径 1:直接写 secure settings(需 WRITE_SECURE_SETTINGS)
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return runCatching {
                if (targets.isEmpty()) {
                    Settings.Secure.putString(context.contentResolver, KEY, "")
                } else {
                    Settings.Secure.putString(context.contentResolver, KEY, targets.joinToString(":"))
                }
                null
            }.getOrElse { "写入失败: ${it.message}" }
        }

        // 路径 2:Shizuku shell(settings 命令)
        if (shizukuExecutor.isAvailable()) {
            if (!changed && enable) return "快捷方式已开启"
            if (!changed && !enable) return "快捷方式已关闭"
            val shellLine = if (targets.isEmpty()) {
                "settings delete secure $KEY"
            } else {
                // 组件名只含 [A-Za-z0-9._/],单引号包裹足够安全
                "settings put secure $KEY '${targets.joinToString(":")}'"
            }
            val result = kotlinx.coroutines.runBlocking {
                runCatching { shizukuExecutor.execShell(shellLine) }.getOrNull()
            } ?: return "Shizuku 执行失败"
            return if (result.success) null else "settings 失败: ${result.output.take(120)}"
        }

        return "需要 WRITE_SECURE_SETTINGS 或 Shizuku 授权后才能修改"
    }

    companion object {
        /** Android 8.0+ 无障碍快捷方式目标服务(Settings.Secure 未公开常量)。 */
        private const val KEY = "accessibility_shortcut_target_service"
    }
}
