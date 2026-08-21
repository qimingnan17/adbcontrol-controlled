package com.adbcontrol.controlled.telemetry

import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.StatusReport
import kotlinx.serialization.json.Json

/**
 * 基础状态回报。README 5.1。
 *
 * - 触发:状态变化(电量±5%/网络切换/屏幕开关)+ 兜底每 5 分钟
 * - QoS 0(README 10.2.3 缓解策略 #1:STATUS 降为 QoS 0 减存储压力)
 */
class StatusReporter(
    private val mqttManager: MqttManager,
    private val collector: SystemInfoCollector,
    private val json: Json,
) {

    @Volatile private var lastBattery = -1
    @Volatile private var lastNetwork: StatusReport.NetworkType? = null
    @Volatile private var lastScreenOn: Boolean? = null

    /** 采集并上报一次。返回 true 表示已发送。 */
    fun reportOnce(deviceId: String): Boolean {
        val (network, dbm) = collector.network()
        val battery = collector.batteryLevel()
        val charging = collector.isCharging()
        val screenOn = collector.isScreenOn()
        val fg = collector.foregroundPackage()

        val report = StatusReport(
            deviceId = deviceId,
            battery = battery,
            charging = charging,
            network = network,
            signalDbm = dbm,
            screenOn = screenOn,
            foregroundPackage = fg,
            timestamp = System.currentTimeMillis(),
        )
        val payload = json.encodeToString(StatusReport.serializer(), report)
        return mqttManager.publishTelemetry(
            MessageType.PUSH_DATA, payload, "status/$deviceId", qos = 0
        ).also {
            lastBattery = battery
            lastNetwork = network
            lastScreenOn = screenOn
        }
    }

    /** 是否有显著变化(电量±5 / 网络切换 / 屏幕开关)。 */
    fun hasSignificantChange(): Boolean {
        val battery = collector.batteryLevel()
        val (network, _) = collector.network()
        val screenOn = collector.isScreenOn()
        val batteryChanged = lastBattery < 0 || kotlin.math.abs(battery - lastBattery) >= 5
        val networkChanged = network != lastNetwork
        val screenChanged = screenOn != lastScreenOn
        return batteryChanged || networkChanged || screenChanged
    }
}
