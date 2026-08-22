package com.adbcontrol.controlled.apptime

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.model.AppTimeWindows
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

    /** 包名 → 禁用时间窗("HH:mm");支持跨零点(如 22:00-07:00)。 */
    private val windows = mutableMapOf<String, Pair<String, String>>()

    /** 因累计时长超限而 suspend 的包。 */
    private val limitSuspended = mutableSetOf<String>()

    /** 因处于禁用时间窗而 suspend 的包。 */
    private val windowSuspended = mutableSetOf<String>()

    /** 上次采样所属"一年中的第几天"(跨天自动重置累计超限标记)。 */
    @Volatile private var lastSampleDay: Int = -1

    // 配置持久化:进程被回收重启后仍能继续执行已下发的管控。
    private val prefs by lazy {
        context.getSharedPreferences("app_time_config", Context.MODE_PRIVATE)
    }

    init {
        // 启动即从磁盘恢复:"pkg=60" / "pkg=22:00-07:00"
        prefs.getStringSet(KEY_LIMITS, emptySet()).orEmpty().forEach { line ->
            val idx = line.lastIndexOf('=')
            if (idx > 0) {
                val pkg = line.substring(0, idx)
                line.substring(idx + 1).toIntOrNull()?.let { limits[pkg] = it }
            }
        }
        prefs.getStringSet(KEY_WINDOWS, emptySet()).orEmpty().forEach { line ->
            val eq = line.lastIndexOf('=')
            if (eq > 0) {
                val pkg = line.substring(0, eq)
                val range = line.substring(eq + 1).split("-", limit = 2)
                if (range.size == 2 && parseHm(range[0]) >= 0 && parseHm(range[1]) >= 0) {
                    windows[pkg] = range[0] to range[1]
                }
            }
        }
    }

    private fun persist() {
        val l = synchronized(limits) { limits.map { "${it.key}=${it.value}" }.toSet() }
        val w = synchronized(windows) { windows.map { "${it.key}=${it.value.first}-${it.value.second}" }.toSet() }
        prefs.edit().putStringSet(KEY_LIMITS, l).putStringSet(KEY_WINDOWS, w).apply()
    }

    fun setLimits(pkgLimitMinutes: Map<String, Int>) {
        synchronized(limits) {
            limits.clear()
            limits.putAll(pkgLimitMinutes)
        }
        persist()
    }

    /** 单包累计时长限制(分钟)。 */
    fun setLimit(pkg: String, minutes: Int) {
        synchronized(limits) { limits[pkg] = minutes }
        persist()
    }

    /** 清除单包累计时长限制。 */
    fun clearLimit(pkg: String) {
        synchronized(limits) { limits.remove(pkg) }
        synchronized(limitSuspended) { limitSuspended.remove(pkg) }
        persist()
        scope.launch { unsuspend(pkg) }
    }

    /** 设置单包禁用时间窗("HH:mm",支持跨零点)。 */
    fun setWindow(pkg: String, start: String, end: String) {
        synchronized(windows) { windows[pkg] = start to end }
        persist()
    }

    /** 清除单包禁用时间窗。 */
    fun clearWindow(pkg: String) {
        synchronized(windows) { windows.remove(pkg) }
        synchronized(windowSuspended) { windowSuspended.remove(pkg) }
        persist()
        scope.launch { unsuspend(pkg) }
    }

    fun start() {
        stop()
        // 每 1 分钟采样,保证时间窗精度(1 分钟内偏差可接受)
        jobs += scope.launch {
            while (true) {
                delay(60_000L)
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
        // 跨天重置:UsageStats 只统计今天,昨天的超限标记必须清掉并尝试恢复
        val today = cal.get(Calendar.DAY_OF_YEAR)
        if (lastSampleDay != today) {
            val toRecover = synchronized(limitSuspended) { limitSuspended.toSet() }
            synchronized(limitSuspended) { limitSuspended.clear() }
            for (pkg in toRecover) unsuspend(pkg)
            lastSampleDay = today
        }
        val start = cal.timeInMillis
        val now = System.currentTimeMillis()
        val stats = runCatching { usm.queryAndAggregateUsageStats(start, now) }
            .getOrNull() ?: return

        // 1) 时长限制判定
        val toSuspendLimit = mutableListOf<Pair<String, Int>>()
        synchronized(limits) {
            for ((pkg, limitMin) in limits) {
                val alreadySuspended = synchronized(limitSuspended) { pkg in limitSuspended }
                if (alreadySuspended) continue
                val used = (stats[pkg]?.totalTimeInForeground ?: 0L) / 60_000L
                if (used >= limitMin && limitMin > 0) {
                    toSuspendLimit += pkg to used.toInt()
                }
            }
        }
        for ((pkg, used) in toSuspendLimit) {
            if (suspendPkg(pkg, "limit_exceeded", used)) {
                synchronized(limitSuspended) { limitSuspended += pkg }
                notifyLimitReached(pkg, used)
            }
        }

        // 2) 时间窗判定(独立于时长,进出窗切换可实时 suspend/unsuspend)。
        // 先在临界区内算出动作列表,再出临界区执行(dispatcher.dispatch 是 suspend)。
        data class WinAction(val pkg: String, val toSuspend: Boolean)
        val nowMin = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
        }
        val winActions = mutableListOf<WinAction>()
        val snapshot: Map<String, Pair<String, String>> = synchronized(windows) { windows.toMap() }
        for ((pkg, win) in snapshot) {
            val startMin = AppTimeWindows.parseHm(win.first)
            val endMin = AppTimeWindows.parseHm(win.second)
            if (startMin < 0 || endMin < 0) continue
            val inWindow = AppTimeWindows.isInWindow(nowMin, startMin, endMin)
            val wasSuspended = synchronized(windowSuspended) { pkg in windowSuspended }
            if (inWindow && !wasSuspended) winActions += WinAction(pkg, true)
            else if (!inWindow && wasSuspended) winActions += WinAction(pkg, false)
        }
        for (action in winActions) {
            if (action.toSuspend) {
                if (suspendPkg(action.pkg, "window_start")) {
                    synchronized(windowSuspended) { windowSuspended += action.pkg }
                }
            } else {
                unsuspend(action.pkg)
                synchronized(windowSuspended) { windowSuspended.remove(action.pkg) }
            }
        }
    }

    /** 主控重置(次日或配置变更)后清除 suspend 标记。 */
    fun reset(pkg: String? = null) {
        synchronized(limitSuspended) {
            if (pkg == null) limitSuspended.clear() else limitSuspended.remove(pkg)
        }
        synchronized(windowSuspended) {
            if (pkg == null) windowSuspended.clear() else windowSuspended.remove(pkg)
        }
    }

    /** 解除 suspend(Shizuku am unsuspend,失败尝试 DeviceAdmin unhide)。 */
    private suspend fun unsuspend(pkg: String) {
        dispatcher.dispatch(
            Command(category = CommandCategory.APP_TIME, action = "unsuspend", params = mapOf("pkg" to pkg)),
            "apptime-unsuspend-${System.nanoTime()}",
        )
    }

    /** 执行 suspend,返回是否成功。 */
    private suspend fun suspendPkg(pkg: String, reason: String, usedMin: Int? = null): Boolean {
        val params = mutableMapOf("pkg" to pkg, "reason" to reason)
        if (usedMin != null) params["usedMin"] = usedMin.toString()
        val cmd = Command(category = CommandCategory.APP_TIME, action = "suspend", params = params)
        return dispatcher.dispatch(cmd, "apptime-suspend-${System.nanoTime()}").success
    }

    /** "HH:mm" → 当天分钟数,非法返回 -1。委托 shared [AppTimeWindows] 保持单测可测。 */
    internal fun parseHm(hm: String): Int = AppTimeWindows.parseHm(hm)

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

    companion object {
        private const val TAG = "AppTimeController"
        private const val KEY_LIMITS = "limits"
        private const val KEY_WINDOWS = "windows"
    }
}
