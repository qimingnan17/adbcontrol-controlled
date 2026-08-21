package com.adbcontrol.controlled.telemetry

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.UsageItem
import com.adbcontrol.shared.model.UsageReport
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 应用使用时长聚合上报。README 5.5 / 10.2.3 缓解策略 #5。
 *
 * - 整点上报,按 deviceId hash 错峰到整点内不同分钟(offset = abs(deviceId.hashCode()) % 60)
 * - UsageStatsManager.queryAndAggregateUsageStats 取当日各包时长
 * - QoS 1
 */
class UsageReporter(
    private val context: Context,
    private val mqttManager: MqttManager,
    private val json: Json,
) {

    /** 错峰偏移分钟(README 10.2.3 #5)。 */
    fun offsetMinutes(deviceId: String): Int = kotlin.math.abs(deviceId.hashCode()) % 60

    /** 采集当日各 App 使用时长并上报。 */
    fun reportOnce(deviceId: String, userId: String = "0"): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val stats = runCatching {
            usm.queryAndAggregateUsageStats(start, end)
        }.getOrElse {
            Log.w(TAG, "queryAndAggregateUsageStats failed", it)
            return false
        }

        val pm = context.packageManager
        val items = stats.orEmpty().mapNotNull { (pkg, stat) ->
            val minutes = (stat.totalTimeInForeground / 60_000L).toInt()
            if (minutes <= 0) return@mapNotNull null
            val appName = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
            UsageItem(pkg = pkg, appName = appName, usageMinutes = minutes)
        }.sortedByDescending { it.usageMinutes }

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(start))
        val report = UsageReport(
            deviceId = deviceId,
            userId = userId,
            date = dateStr,
            items = items,
            timestamp = System.currentTimeMillis(),
        )
        val payload = json.encodeToString(UsageReport.serializer(), report)
        return mqttManager.publishTelemetry(
            MessageType.PUSH_DATA, payload, "usage/$deviceId", qos = 1
        )
    }

    companion object { private const val TAG = "UsageReporter" }
}
