package com.adbcontrol.controlled.executor

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import com.adbcontrol.controlled.admin.ControlledDeviceAdminReceiver
import com.adbcontrol.controlled.admin.DeviceAdminComponent
import com.adbcontrol.controlled.oem.MiuiAdapter
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.model.ExecutionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeviceAdmin 执行器。README 3.3 L4。
 *
 * 提供系统能力 API 路径(需 DeviceAdmin 激活):
 * - lockNow(锁屏):MIUI 上先尝试已知锁屏广播兜底,再 dpm.lockNow()(已锁屏则 no-op)
 * - setApplicationHidden(隐藏应用,替代 force-stop 兜底)
 * - setUninstallBlocked(防卸载)
 * - setApplicationEnabled(suspend 兜底,需 DeviceOwner)
 */
@Singleton
class DeviceAdminExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val miuiAdapter: MiuiAdapter,
) : CommandExecutor {

    override val name: String = "DeviceAdmin"

    private val adminComponent by lazy {
        android.content.ComponentName(context, ControlledDeviceAdminReceiver::class.java)
    }

    private val dpm: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    override fun isAvailable(): Boolean =
        DeviceAdminComponent.refresh(context, adminComponent)

    override fun supports(command: Command): Boolean = when (command.category) {
        CommandCategory.SYSTEM -> command.action == "lockScreen"
        CommandCategory.APP_TIME -> command.action == "suspend" || command.action == "unsuspend" ||
            command.action == "hide" || command.action == "unhide"
        CommandCategory.APP -> command.action == "setUninstallBlocked"
        else -> false
    }

    override suspend fun execute(command: Command, commandId: String): ExecutionResult {
        if (!isAvailable()) return fail(commandId, "DEVICE_ADMIN_INACTIVE")
        val started = System.currentTimeMillis()
        return runCatching {
            when {
                command.category == CommandCategory.SYSTEM && command.action == "lockScreen" -> {
                    // MIUI 上 DeviceAdmin lockNow() 行为略不同(可能延迟或弹锁屏组件),
                    // 先尝试已知 MIUI 锁屏广播兜底,再 dpm.lockNow()(已锁屏则 no-op)。
                    if (miuiAdapter.isMiui()) {
                        runCatching { miuiAdapter.lockScreenViaBroadcast() }
                    }
                    dpm.lockNow()
                    ok(commandId, "OK", System.currentTimeMillis() - started)
                }
                command.category == CommandCategory.APP_TIME -> executeAppTime(command, commandId, started)
                command.category == CommandCategory.APP && command.action == "setUninstallBlocked" -> {
                    val pkg = command.params["pkg"] ?: return fail(commandId, "missing pkg")
                    val blocked = command.params["blocked"]?.toBooleanStrictOrNull() ?: true
                    dpm.setUninstallBlocked(adminComponent, pkg, blocked)
                    ok(commandId, "OK", System.currentTimeMillis() - started)
                }
                else -> noPath(commandId)
            }
        }.getOrElse {
            fail(commandId, "exception=${it.message}", System.currentTimeMillis() - started)
        }
    }

    private fun executeAppTime(c: Command, commandId: String, started: Long): ExecutionResult {
        val pkg = c.params["pkg"] ?: return fail(commandId, "missing pkg")
        val ok = when (c.action) {
            "hide" -> dpm.setApplicationHidden(adminComponent, pkg, true)
            "unhide" -> dpm.setApplicationHidden(adminComponent, pkg, false)
            // suspend 需 DeviceOwner/ProfileOwner,普通 DeviceAdmin 不支持
            "suspend", "unsuspend" -> false
            else -> return noPath(commandId)
        }
        return if (ok) ok(commandId, "OK", System.currentTimeMillis() - started)
        else fail(commandId, "requires DeviceOwner for ${c.action}", System.currentTimeMillis() - started)
    }

    companion object {
        @Suppress("unused")
        private val BUILD: Int = Build.VERSION.SDK_INT
    }
}
