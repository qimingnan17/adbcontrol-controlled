package com.adbcontrol.controlled.telemetry

import android.content.Context
import android.content.pm.PackageManager
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.ActivityReport
import com.adbcontrol.shared.model.ActivityReport.ActivityEvent
import kotlinx.serialization.json.Json

/**
 * 应用行为上报。README 5.4。
 *
 * 采集优先级:
 * 1. AccessibilityService TYPE_WINDOW_STATE_CHANGED(由 [com.adbcontrol.controlled.accessibility.ControlledAccessibilityService] 调 [report])
 * 2. Shizuku dumpsys activity(后续接入)
 * 3. UsageStatsManager queryEvents(兜底)
 *
 * 上报:前台切换事件 QoS 1。
 */
class ActivityReporter(
    private val context: Context,
    private val mqttManager: MqttManager,
    private val json: Json,
) {

    @Volatile private var currentPkg: String? = null
    @Volatile private var currentEnteredAt: Long = 0

    /** 由 AccessibilityService 在窗口切换时调用。 */
    fun report(
        deviceId: String,
        pkg: String,
        event: ActivityEvent,
        userId: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val appName = appName(pkg)

        // 计算 durationMs:从上一个前台包切换走时填
        var durationMs = 0L
        if (event == ActivityEvent.APP_BACKGROUND && currentPkg == pkg && currentEnteredAt > 0) {
            durationMs = now - currentEnteredAt
        }

        if (event == ActivityEvent.APP_FOREGROUND) {
            currentPkg = pkg
            currentEnteredAt = now
        } else if (event == ActivityEvent.APP_BACKGROUND && currentPkg == pkg) {
            currentPkg = null
            currentEnteredAt = 0
        }

        val report = ActivityReport(
            deviceId = deviceId,
            userId = userId,
            event = event,
            pkg = pkg,
            appName = appName,
            durationMs = durationMs,
            timestamp = now,
        )
        val payload = json.encodeToString(ActivityReport.serializer(), report)
        mqttManager.publishTelemetry(
            MessageType.PUSH_DATA, payload, "activity/$deviceId", qos = 1
        )
    }

    private fun appName(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
