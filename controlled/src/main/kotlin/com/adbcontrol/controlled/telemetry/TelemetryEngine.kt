package com.adbcontrol.controlled.telemetry

import android.util.Log
import com.adbcontrol.shared.model.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 遥测引擎。README 第五章。
 *
 * 周期(README 5.x):
 * - Status:5 分钟 + 显著变化触发(QoS 0)
 * - Location:15 分钟(QoS 0)
 * - Activity:事件驱动(由 AccessibilityService 直接调 reporter)
 * - Usage:整点错峰(QoS 1)
 * - Health:30 分钟(QoS 1)
 *
 * 启动时全量上报一次 Health + Status。
 */
class TelemetryEngine(
    private val statusReporter: StatusReporter,
    private val locationReporter: LocationReporter,
    private val activityReporter: ActivityReporter,
    private val usageReporter: UsageReporter,
    private val healthReporter: HealthReporter,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    @Volatile private var deviceId: String = ""

    fun start(config: AppConfig) {
        stop()
        deviceId = config.deviceId
        // 启动即全量
        runOnce { healthReporter.reportOnce(deviceId) }
        runOnce { statusReporter.reportOnce(deviceId) }

        // Status:5 分钟 + 显著变化(每 30 秒检查变化)
        jobs += scope.launch {
            while (true) {
                delay(TimeUnit.SECONDS.toMillis(30))
                if (statusReporter.hasSignificantChange()) {
                    statusReporter.reportOnce(deviceId)
                } else {
                    // 兜底每 5 分钟
                    delay(TimeUnit.MINUTES.toMillis(5) - TimeUnit.SECONDS.toMillis(30))
                    statusReporter.reportOnce(deviceId)
                }
            }
        }

        // Location:15 分钟
        jobs += scope.launch {
            while (true) {
                locationReporter.reportOnce(deviceId)
                delay(TimeUnit.MINUTES.toMillis(15))
            }
        }

        // Health:30 分钟
        jobs += scope.launch {
            while (true) {
                healthReporter.reportOnce(deviceId)
                delay(TimeUnit.MINUTES.toMillis(30))
            }
        }

        // Usage:整点错峰
        jobs += scope.launch { scheduleUsageLoop() }

        Log.i(TAG, "telemetry engine started for $deviceId")
    }

    private suspend fun scheduleUsageLoop() {
        while (true) {
            val offset = usageReporter.offsetMinutes(deviceId).toLong()
            val (delayUntilNext, minutesOfDay) = minutesUntilNextHour(offset)
            Log.d(TAG, "usage loop: next in ${delayUntilNext}ms (offset=${offset}min, now=$minutesOfDay)")
            delay(delayUntilNext)
            runOnce { usageReporter.reportOnce(deviceId) }
        }
    }

    /** 计算到下一个"整点 + offset 分钟"的延迟(毫秒)。 */
    private fun minutesUntilNextHour(offsetMinutes: Long): Pair<Long, Long> {
        val now = Calendar.getInstance()
        val minutesOfDay = now.get(Calendar.HOUR_OF_DAY) * 60L + now.get(Calendar.MINUTE)
        // 下一个目标分钟(本小时 offset 已过则取下一整点 + offset;offset 始终 < 60)
        // target = (当前小时 + 1) * 60 + offset,即下一个整点之后 offset 分钟
        val target = (now.get(Calendar.HOUR_OF_DAY) + 1) * 60L + offsetMinutes
        // delayMinutes 必为正(target 在未来),范围 (0, 119]
        var delayMinutes = target - minutesOfDay
        if (delayMinutes <= 0L) {
            // 极端情况(刚好跨分钟):等到再下一整点 offset
            delayMinutes += 60
        }
        return Pair(TimeUnit.MINUTES.toMillis(delayMinutes), minutesOfDay)
    }

    /** 由 AccessibilityService/NotificationListener 调用,即时上报窗口/通知事件。 */
    fun reportActivity(pkg: String, event: com.adbcontrol.shared.model.ActivityReport.ActivityEvent) {
        // Bug 7:ControlledService 启动前(或未配对),NotificationListener/AccessibilityService 会先于服务被系统
        // 自动连接并调用本方法,此时 deviceId 为默认空串 "",publish 到 topic "activity/" 会被 EMQX 拒绝
        // 或入库 deviceId="" 的脏数据。空 deviceId 全部 return。
        if (deviceId.isEmpty()) return
        scope.launch {
            runCatching { activityReporter.report(deviceId, pkg, event) }
                .onFailure { Log.w(TAG, "activity report failed", it) }
        }
    }

    private fun runOnce(block: () -> Boolean) {
        // 与 reportActivity 同理:start() 调用前或 stop() 后 deviceId 为空时跳过所有周期上报
        if (deviceId.isEmpty()) return
        scope.launch {
            runCatching { block() }.onFailure { Log.w(TAG, "report failed", it) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        Log.i(TAG, "telemetry engine stopped")
    }

    companion object { private const val TAG = "TelemetryEngine" }
}
