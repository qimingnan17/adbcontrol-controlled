package com.adbcontrol.controlled.telemetry

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.HealthReport
import com.adbcontrol.shared.model.HealthReport.ShizukuState
import kotlinx.serialization.json.Json

/**
 * 设备健康上报。README 5.5。
 *
 * - 启动时全量上报 + 每 30 分钟增量上报
 * - 主控端 UI 据此画能力雷达
 */
class HealthReporter(
    private val context: Context,
    private val dispatcher: CommandDispatcher,
    private val collector: SystemInfoCollector,
    private val appVersion: String,
    private val mqttManager: MqttManager,
    private val json: Json,
) {

    @Volatile private var lastBootAt: Long = System.currentTimeMillis() // TODO: 后续接入 HealthReport.lastBootAt 字段

    /** 上报一次健康。 */
    fun reportOnce(deviceId: String): Boolean {
        val cap = dispatcher.snapshot(
            usageStats = collector.hasUsageStatsPermission(),
            batteryWhitelist = isBatteryWhitelisted(),
        )

        val report = HealthReport(
            deviceId = deviceId,
            mqtt = mqttManager.isReady(),
            service = true, // service 运行时才上报
            shizuku = parseShizuku(cap.shizuku),
            root = cap.root,
            accessibility = cap.accessibility,
            deviceAdmin = cap.deviceAdmin,
            usageStats = cap.usageStats,
            batteryWhitelist = cap.batteryWhitelist,
            androidVersion = collector.androidVersion,
            appVersion = appVersion,
            timestamp = System.currentTimeMillis(),
        )
        val payload = json.encodeToString(HealthReport.serializer(), report)
        return mqttManager.publishTelemetry(
            MessageType.PUSH_DATA, payload, "health/$deviceId", qos = 1
        )
    }

    private fun parseShizuku(state: String): ShizukuState = runCatching {
        ShizukuState.valueOf(state)
    }.getOrDefault(ShizukuState.UNSUPPORTED)

    private fun isBatteryWhitelisted(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
}
