package com.adbcontrol.controlled.apptime

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.WsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.util.Calendar

/**
 * 软件定时使用控制。README 3.1.1 / 6.1。
 *
 * 三种模式:
 * - 时间窗口禁用/放开:主控 cron 下发 APP_TIME SUSPEND/UNSUSPEND,经 Shizuku `am suspend` 执行
 * - 累计使用时长限制:被控端周期采样 UsageStatsManager,达阈值自动 suspend + 提醒
 * - 最后 10 分钟提醒:窗口结束前 10 分钟主控发 REMINDER
 *
 * 本类负责本地累计采样 + 自动 suspend;窗口型由主控 cron 驱动,经 [CommandDispatcher] 执行。
 */
class AppTimeController(
    private val context: Context,
    private val dispatcher: CommandDispatcher,
    private val mqttManager: MqttManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()

    /** 包名 → 当日累计上限(分钟)。主控下发配置。 */
    private val limits = mutableMapOf<String, Int>()

    /** 已 suspend 的包(避免重复 suspend)。并发读写需同步,防 ConcurrentModificationException。 */
    private val suspended = mutableSetOf<String>()

    fun setLimits(pkgLimitMinutes: Map<String, Int>) {
        synchronized(limits) {
            limits.clear()
            limits.putAll(pkgLimitMinutes)
        }
    }

    fun start() {
        stop()
        // 每 5 分钟采样一次当日使用时长
        jobs += scope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                sampleAndEnforce()
            }
        }
        Log.i(TAG, "app time controller started")
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** 采样并执行阈值限制。 */
    private suspend fun sampleAndEnforce() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val now = System.currentTimeMillis()
        val stats = runCatching { usm.queryAndAggregateUsageStats(start, now) }
            .getOrNull() ?: return

        val toSuspend = mutableListOf<Pair<String, Int>>() // pkg, usedMinutes
        synchronized(limits) {
            for ((pkg, limitMin) in limits) {
                // 不能在 synchronized 内联 lambda 里 continue(实验性特性),先取出标志位再判断
                val alreadySuspended = synchronized(suspended) { pkg in suspended }
                if (alreadySuspended) continue
                val used = (stats[pkg]?.totalTimeInForeground ?: 0L) / 60_000L
                if (used >= limitMin && limitMin > 0) {
                    toSuspend += pkg to used.toInt()
                }
            }
        }
        for ((pkg, used) in toSuspend) {
            val cmd = Command(
                category = CommandCategory.APP_TIME,
                action = "suspend",
                params = mapOf("pkg" to pkg, "reason" to "limit_exceeded", "usedMin" to used.toString()),
            )
            val result = dispatcher.dispatch(cmd, "apptime-suspend-${System.nanoTime()}")
            if (result.success) {
                synchronized(suspended) { suspended += pkg }
                notifyLimitReached(pkg, used)
            }
        }
    }

    /** 主控重置(次日或配置变更)后清除 suspend 标记。 */
    fun reset(pkg: String? = null) {
        synchronized(suspended) {
            if (pkg == null) suspended.clear() else suspended.remove(pkg)
        }
    }

    private fun notifyLimitReached(pkg: String, usedMin: Int) {
        val deviceId = mqttManager.currentDeviceId() ?: return
        // 用 buildJsonObject 构造,避免 pkg 含 " 或 \ 时 JSON 注入
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("event", "LIMIT_REACHED")
            put("pkg", pkg)
            put("usedMin", usedMin)
        }.toString()
        val msg = WsMessage(
            id = "apptime-notify-${System.nanoTime()}",
            type = MessageType.PUSH_DATA,
            payload = payload,
            timestamp = System.currentTimeMillis(),
        )
        mqttManager.publish(msg, "push/$deviceId", qos = 1)
    }

    companion object { private const val TAG = "AppTimeController" }
}
