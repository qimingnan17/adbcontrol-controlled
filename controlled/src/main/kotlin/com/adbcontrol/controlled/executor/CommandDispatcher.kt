package com.adbcontrol.controlled.executor

import android.util.Log
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.ExecutionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 能力快照。README 3.3 能力检测上报主控端。
 * 字段对齐 [com.adbcontrol.shared.model.HealthReport] 与能力雷达。
 */
data class CapabilitySnapshot(
    val shizuku: String,        // CONNECTED / NOT_AUTHORIZED / NOT_RUNNING / NOT_INSTALLED
    val root: Boolean,
    val accessibility: Boolean,
    val deviceAdmin: Boolean,
    val usageStats: Boolean,
    val batteryWhitelist: Boolean,
)

/**
 * 命令分派器。README 3.3。
 *
 * 优先级:Shizuku → Root → Accessibility → DeviceAdmin → Normal → NO_PATH
 *
 * 选择规则:取第一个 [isAvailable]==true 且 [supports]==true 的执行器执行;
 * 若所有执行器都不支持该命令,返回 NO_PATH。
 */
@Singleton
class CommandDispatcher @Inject constructor(
    private val shizukuExecutor: ShizukuExecutor,
    private val rootExecutor: RootExecutor,
    private val accessibilityExecutor: AccessibilityExecutor,
    private val deviceAdminExecutor: DeviceAdminExecutor,
    private val normalExecutor: NormalExecutor,
) {
    /** 分派优先级链。 */
    private val chain: List<CommandExecutor> = listOf(
        shizukuExecutor,
        rootExecutor,
        accessibilityExecutor,
        deviceAdminExecutor,
        normalExecutor,
    )

    suspend fun dispatch(command: Command, commandId: String): ExecutionResult {
        val matched = chain.firstOrNull { it.isAvailable() && it.supports(command) }
        if (matched == null) {
            Log.w(TAG, "no executor supports command ${command.category}/${command.action}")
            return noPath(commandId)
        }
        Log.i(TAG, "dispatch ${command.category}/${command.action} via ${matched.name}")
        return runCatching {
            matched.execute(command, commandId)
        }.getOrElse {
            fail(commandId, "dispatcher exception=${it.message}")
        }
    }

    /** 收集当前能力快照(供 [com.adbcontrol.controlled.telemetry.HealthReporter] 上报)。 */
    fun snapshot(
        usageStats: Boolean,
        batteryWhitelist: Boolean,
    ): CapabilitySnapshot = CapabilitySnapshot(
        shizuku = shizukuExecutor.state().name,
        root = rootExecutor.isAvailable(),
        accessibility = accessibilityExecutor.isAvailable(),
        deviceAdmin = deviceAdminExecutor.isAvailable(),
        usageStats = usageStats,
        batteryWhitelist = batteryWhitelist,
    )

    /** 仅 Shizuku 状态(频繁刷新用)。 */
    fun shizukuStateName(): String = shizukuExecutor.state().name

    companion object {
        private const val TAG = "CommandDispatcher"
    }
}
