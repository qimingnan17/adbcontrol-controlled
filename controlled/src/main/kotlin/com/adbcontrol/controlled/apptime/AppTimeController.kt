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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 软件定时使用控制。README 3.1.1 / 6.1。
 *
 * 三种模式:
 * - 时间窗口禁用/放开:主控 cron 下发 APP_TIME SUSPEND/UNSUSPEND,经 Shizuku `am suspend` 执行
 * - 累计使用时长限制:被控端周期采样 UsageStatsManager,达阈值自动 suspend + 提醒
 * - 最后 10 分钟提醒:窗口结束前 10 分钟主控发 REMINDER
 *
 * v2.0 新增:
 * - 当日"额外分钟"(延时审批通过,契约 §3 APP_TIME_EXTEND):累加到当日配额,跨天清零
 * - 时间窗临时放行:窗内批准延时 → 在 N 分钟内不受窗口限制,到期自动恢复
 * - [quotas] 状态流:供首页"今日剩余时长"卡片与拦截页展示
 *
 * 本类负责本地累计采样 + 自动 suspend;窗口型由主控 cron 驱动,经 [CommandDispatcher] 执行。
 */
class AppTimeController(
    private val context: Context,
    private val dispatcher: CommandDispatcher,
    private val mqttManager: MqttManager,
) {

    /** 单个受管 App 的当日配额快照(UI 用)。 */
    data class AppQuota(
        val pkg: String,
        val label: String,
        /** 当日累计上限(含额外分钟);null = 无时长限制 */
        val limitMinutes: Int?,
        /** 今日已批准的额外分钟 */
        val bonusMinutes: Int,
        val usedMinutes: Int,
        /** 剩余分钟;null = 无时长限制 */
        val remainingMinutes: Int?,
        /** 禁用时间窗 "HH:mm-HH:mm";null = 无 */
        val window: String?,
        /** 当前是否处于禁用窗口(已扣除临时放行) */
        val inWindowNow: Boolean,
        /** 当前是否受限(超额或处于窗口内) */
        val restricted: Boolean,
        val reason: Reason?,
        /** 预计解封时间(epoch ms);null = 不受限 */
        val unlockAt: Long?,
    )

    enum class Reason { LIMIT_EXCEEDED, TIME_WINDOW }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()

    /** 包名 → 当日累计上限(分钟)。主控下发配置。 */
    private val limits = mutableMapOf<String, Int>()

    /** 包名 → 禁用时间窗("HH:mm");支持跨零点(如 22:00-07:00)。 */
    private val windows = mutableMapOf<String, Pair<String, String>>()

    /** 包名 → 今日额外分钟(延时批准)。[bonusDate] 不是今天时视为空。 */
    private val bonus = mutableMapOf<String, Int>()
    @Volatile private var bonusDate: String = ""

    /** 包名 → 时间窗临时放行截止(epoch ms)。 */
    private val windowOverrideUntil = mutableMapOf<String, Long>()

    /** 因累计时长超限而 suspend 的包。 */
    private val limitSuspended = mutableSetOf<String>()

    /** 因处于禁用时间窗而 suspend 的包。 */
    private val windowSuspended = mutableSetOf<String>()

    /** 最近一次采样的今日前台分钟数(包名 → 分钟)。 */
    @Volatile private var lastUsage: Map<String, Int> = emptyMap()

    /** 上次采样所属"一年中的第几天"(跨天自动重置累计超限标记)。 */
    @Volatile private var lastSampleDay: Int = -1

    private val _quotas = MutableStateFlow<List<AppQuota>>(emptyList())
    /** 所有受管 App(有时长限制或时间窗)的当日配额。每分钟采样后刷新。 */
    val quotas: StateFlow<List<AppQuota>> = _quotas.asStateFlow()

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
        bonusDate = prefs.getString(KEY_BONUS_DATE, "").orEmpty()
        prefs.getStringSet(KEY_BONUS, emptySet()).orEmpty().forEach { line ->
            val idx = line.lastIndexOf('=')
            if (idx > 0) line.substring(idx + 1).toIntOrNull()?.let { bonus[line.substring(0, idx)] = it }
        }
        prefs.getStringSet(KEY_OVERRIDES, emptySet()).orEmpty().forEach { line ->
            val idx = line.lastIndexOf('=')
            if (idx > 0) line.substring(idx + 1).toLongOrNull()?.let { windowOverrideUntil[line.substring(0, idx)] = it }
        }
        rollBonusIfNewDay()
    }

    private fun persist() {
        val l = synchronized(limits) { limits.map { "${it.key}=${it.value}" }.toSet() }
        val w = synchronized(windows) { windows.map { "${it.key}=${it.value.first}-${it.value.second}" }.toSet() }
        val b = synchronized(bonus) { bonus.map { "${it.key}=${it.value}" }.toSet() }
        val o = synchronized(windowOverrideUntil) { windowOverrideUntil.map { "${it.key}=${it.value}" }.toSet() }
        prefs.edit()
            .putStringSet(KEY_LIMITS, l)
            .putStringSet(KEY_WINDOWS, w)
            .putStringSet(KEY_BONUS, b)
            .putString(KEY_BONUS_DATE, bonusDate)
            .putStringSet(KEY_OVERRIDES, o)
            .apply()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 额外分钟只在批准当日有效:日期变化即清空。 */
    private fun rollBonusIfNewDay() {
        val t = today()
        if (bonusDate != t) {
            synchronized(bonus) { bonus.clear() }
            bonusDate = t
            persist()
        }
    }

    fun setLimits(pkgLimitMinutes: Map<String, Int>) {
        synchronized(limits) {
            limits.clear()
            limits.putAll(pkgLimitMinutes)
        }
        persist()
        publishQuotas()
    }

    /** 单包累计时长限制(分钟)。 */
    fun setLimit(pkg: String, minutes: Int) {
        synchronized(limits) { limits[pkg] = minutes }
        persist()
        publishQuotas()
    }

    /** 清除单包累计时长限制。 */
    fun clearLimit(pkg: String) {
        synchronized(limits) { limits.remove(pkg) }
        synchronized(limitSuspended) { limitSuspended.remove(pkg) }
        persist()
        publishQuotas()
        scope.launch { syncSuspension(pkg) }
    }

    /** 设置单包禁用时间窗("HH:mm",支持跨零点)。 */
    fun setWindow(pkg: String, start: String, end: String) {
        synchronized(windows) { windows[pkg] = start to end }
        persist()
        publishQuotas()
    }

    /** 清除单包禁用时间窗。 */
    fun clearWindow(pkg: String) {
        synchronized(windows) { windows.remove(pkg) }
        synchronized(windowSuspended) { windowSuspended.remove(pkg) }
        synchronized(windowOverrideUntil) { windowOverrideUntil.remove(pkg) }
        persist()
        publishQuotas()
        scope.launch { syncSuspension(pkg) }
    }

    /**
     * v2.0 契约 §3:延时审批通过。
     * - 有时长限制:把 [minutes] 加到今日配额(跨天失效),若已超限而被 suspend 则解封
     * - 当前处于禁用窗口:临时放行 [minutes] 分钟,到期由采样循环自动恢复 suspend
     * - 都没有(配置已被清掉):仅确保解封
     */
    fun extend(pkg: String, minutes: Int) {
        if (minutes <= 0) return
        rollBonusIfNewDay()
        val hasLimit = synchronized(limits) { pkg in limits }
        if (hasLimit) {
            synchronized(bonus) { bonus[pkg] = (bonus[pkg] ?: 0) + minutes }
            synchronized(limitSuspended) { limitSuspended.remove(pkg) }
        }
        val win = synchronized(windows) { windows[pkg] }
        if (win != null && isInWindowRaw(win)) {
            val base = maxOf(System.currentTimeMillis(), synchronized(windowOverrideUntil) { windowOverrideUntil[pkg] ?: 0L })
            synchronized(windowOverrideUntil) { windowOverrideUntil[pkg] = base + minutes * 60_000L }
            synchronized(windowSuspended) { windowSuspended.remove(pkg) }
        }
        persist()
        Log.i(TAG, "extend $pkg +${minutes}min (limit=$hasLimit, window=${win != null})")
        scope.launch {
            syncSuspension(pkg)
            sampleAndEnforce()
        }
    }

    fun start() {
        stop()
        // 每 1 分钟采样,保证时间窗精度(1 分钟内偏差可接受)
        jobs += scope.launch {
            sampleAndEnforce()
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

    /** 立即重新采样(UI 下拉刷新 / 拦截页打开时调用)。 */
    fun refreshNow() {
        scope.launch { sampleAndEnforce() }
    }

    private fun effectiveLimit(pkg: String): Int? {
        val base = synchronized(limits) { limits[pkg] } ?: return null
        if (base <= 0) return null
        return base + synchronized(bonus) { bonus[pkg] ?: 0 }
    }

    private fun nowMinuteOfDay(): Int = Calendar.getInstance().let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    private fun isInWindowRaw(win: Pair<String, String>): Boolean {
        val startMin = AppTimeWindows.parseHm(win.first)
        val endMin = AppTimeWindows.parseHm(win.second)
        if (startMin < 0 || endMin < 0) return false
        return AppTimeWindows.isInWindow(nowMinuteOfDay(), startMin, endMin)
    }

    /** 窗口判定(扣除临时放行)。 */
    private fun isInWindowEffective(pkg: String, win: Pair<String, String>): Boolean {
        if (!isInWindowRaw(win)) return false
        val until = synchronized(windowOverrideUntil) { windowOverrideUntil[pkg] } ?: return true
        return System.currentTimeMillis() >= until
    }

    /** 采样并执行阈值限制。 */
    private suspend fun sampleAndEnforce() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // 跨天重置:UsageStats 只统计今天,昨天的超限标记与额外分钟必须清掉并尝试恢复
        val today = cal.get(Calendar.DAY_OF_YEAR)
        if (lastSampleDay != today) {
            rollBonusIfNewDay()
            val toRecover = synchronized(limitSuspended) { limitSuspended.toSet() }
            synchronized(limitSuspended) { limitSuspended.clear() }
            for (pkg in toRecover) syncSuspension(pkg)
            lastSampleDay = today
        }
        val start = cal.timeInMillis
        val now = System.currentTimeMillis()
        val stats = runCatching { usm.queryAndAggregateUsageStats(start, now) }.getOrNull()
        if (stats != null) {
            lastUsage = stats.mapValues { (it.value.totalTimeInForeground / 60_000L).toInt() }
        }

        // 1) 时长限制判定
        val toSuspendLimit = mutableListOf<Pair<String, Int>>()
        val limitPkgs = synchronized(limits) { limits.keys.toList() }
        for (pkg in limitPkgs) {
            val alreadySuspended = synchronized(limitSuspended) { pkg in limitSuspended }
            if (alreadySuspended) continue
            val limitMin = effectiveLimit(pkg) ?: continue
            val used = lastUsage[pkg] ?: 0
            if (used >= limitMin) toSuspendLimit += pkg to used
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
        val winActions = mutableListOf<WinAction>()
        val snapshot: Map<String, Pair<String, String>> = synchronized(windows) { windows.toMap() }
        for ((pkg, win) in snapshot) {
            val inWindow = isInWindowEffective(pkg, win)
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
                synchronized(windowSuspended) { windowSuspended.remove(action.pkg) }
                syncSuspension(action.pkg)
            }
        }
        // 过期的临时放行清理
        val expired = synchronized(windowOverrideUntil) {
            windowOverrideUntil.filterValues { it < now - 60_000L }.keys.toList()
        }
        if (expired.isNotEmpty()) {
            synchronized(windowOverrideUntil) { expired.forEach { windowOverrideUntil.remove(it) } }
            persist()
        }
        publishQuotas()
    }

    /** 重新计算 [quotas]。 */
    private fun publishQuotas() {
        val pkgs = (synchronized(limits) { limits.keys.toSet() } + synchronized(windows) { windows.keys.toSet() })
        _quotas.value = pkgs.map { quotaFor(it) }.sortedWith(
            compareByDescending<AppQuota> { it.restricted }.thenBy { it.remainingMinutes ?: Int.MAX_VALUE }
        )
    }

    /** 单个包当前配额(未受管也返回一个无限制的快照)。 */
    fun quotaFor(pkg: String): AppQuota {
        val limit = effectiveLimit(pkg)
        val used = lastUsage[pkg] ?: 0
        val remaining = limit?.let { (it - used).coerceAtLeast(0) }
        val win = synchronized(windows) { windows[pkg] }
        val inWin = win != null && isInWindowEffective(pkg, win)
        val overLimit = limit != null && used >= limit ||
            synchronized(limitSuspended) { pkg in limitSuspended }
        val restricted = overLimit || inWin
        val reason = when {
            inWin -> Reason.TIME_WINDOW
            overLimit -> Reason.LIMIT_EXCEEDED
            else -> null
        }
        val unlockAt = if (!restricted) null else {
            val candidates = mutableListOf<Long>()
            if (overLimit) candidates += nextMidnight()
            if (inWin && win != null) candidates += windowEndMillis(win)
            candidates.maxOrNull()
        }
        return AppQuota(
            pkg = pkg,
            label = labelOf(pkg),
            limitMinutes = limit,
            bonusMinutes = synchronized(bonus) { bonus[pkg] ?: 0 },
            usedMinutes = used,
            remainingMinutes = remaining,
            window = win?.let { "${it.first}-${it.second}" },
            inWindowNow = inWin,
            restricted = restricted,
            reason = reason,
            unlockAt = unlockAt,
        )
    }

    /** 是否受管(配置了时长限制或时间窗)。 */
    fun isManaged(pkg: String): Boolean =
        synchronized(limits) { pkg in limits } || synchronized(windows) { pkg in windows }

    /** 当前是否受限(供无障碍服务判断是否弹拦截页,不依赖 suspend 是否成功)。 */
    fun isRestricted(pkg: String): Boolean = isManaged(pkg) && quotaFor(pkg).restricted

    /** 当前所有受限包。 */
    fun restrictedPackages(): List<String> =
        (synchronized(limits) { limits.keys.toSet() } + synchronized(windows) { windows.keys.toSet() })
            .filter { quotaFor(it).restricted }

    private fun nextMidnight(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun windowEndMillis(win: Pair<String, String>): Long {
        val endMin = AppTimeWindows.parseHm(win.second).coerceAtLeast(0)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, endMin / 60); set(Calendar.MINUTE, endMin % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun labelOf(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 统一解封同步:只有既不在限时超额名单、也不在时间窗名单内,才执行真实 unsuspend */
    private suspend fun syncSuspension(pkg: String) {
        val shouldBeSuspended = synchronized(limitSuspended) { pkg in limitSuspended } ||
            synchronized(windowSuspended) { pkg in windowSuspended }
        if (!shouldBeSuspended) {
            unsuspend(pkg)
        }
    }

    /** 主控重置(次日或配置变更)后清除 suspend 标记。 */
    fun reset(pkg: String? = null) {
        val pkgs = synchronized(limitSuspended) {
            if (pkg == null) limitSuspended.toSet() else setOf(pkg)
        } + synchronized(windowSuspended) {
            if (pkg == null) windowSuspended.toSet() else setOf(pkg)
        }
        synchronized(limitSuspended) {
            if (pkg == null) limitSuspended.clear() else limitSuspended.remove(pkg)
        }
        synchronized(windowSuspended) {
            if (pkg == null) windowSuspended.clear() else windowSuspended.remove(pkg)
        }
        scope.launch {
            for (p in pkgs) syncSuspension(p)
            publishQuotas()
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
        private const val KEY_BONUS = "bonus"
        private const val KEY_BONUS_DATE = "bonus_date"
        private const val KEY_OVERRIDES = "window_overrides"
    }
}
