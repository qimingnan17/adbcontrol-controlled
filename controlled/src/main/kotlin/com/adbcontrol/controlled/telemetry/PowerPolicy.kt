package com.adbcontrol.controlled.telemetry

import android.location.Location
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * v2.0 省电策略:电量 < 20%(且未充电)或设备静止时,降低定位 / 心跳(状态兜底)/ 健康上报频率。
 *
 * | 模式         | 定位   | 状态兜底 | 显著变化扫描 | 健康   |
 * |--------------|--------|----------|--------------|--------|
 * | NORMAL       | 5 min  | 2 min    | 10 s         | 10 min |
 * | STATIONARY   | 15 min | 4 min    | 10 s         | 10 min |
 * | LOW_BATTERY  | 30 min | 10 min   | 30 s         | 30 min |
 *
 * 静止判定:连续 [STATIONARY_AFTER_MS] 内所有定位点都落在锚点 [STATIONARY_RADIUS_M] 米内且速度 < 1 m/s。
 * MQTT keepAlive 由配对下发的 broker 配置决定,这里不改(改动需重连,得不偿失)。
 * 15 分钟准实时用量上报(契约 §4)不受省电模式影响。
 */
class PowerPolicy(private val collector: SystemInfoCollector) {

    enum class Mode { NORMAL, STATIONARY, LOW_BATTERY }

    private val _mode = MutableStateFlow(Mode.NORMAL)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    @Volatile private var anchor: Location? = null
    @Volatile private var anchorSince: Long = 0L

    /** 重新评估当前模式(读电量 + 静止状态)。 */
    fun evaluate(): Mode {
        val battery = runCatching { collector.batteryLevel() }.getOrDefault(100)
        val charging = runCatching { collector.isCharging() }.getOrDefault(false)
        val m = when {
            battery in 0 until LOW_BATTERY_THRESHOLD && !charging -> Mode.LOW_BATTERY
            isStationary() -> Mode.STATIONARY
            else -> Mode.NORMAL
        }
        if (m != _mode.value) Log.i(TAG, "power mode ${_mode.value} -> $m (battery=$battery charging=$charging)")
        _mode.value = m
        return m
    }

    /** 喂入新定位点,更新静止判定。 */
    fun onLocation(loc: Location) {
        val a = anchor
        val now = System.currentTimeMillis()
        val moving = loc.hasSpeed() && loc.speed >= 1f
        if (a == null || moving || a.distanceTo(loc) > STATIONARY_RADIUS_M + loc.accuracy.coerceAtMost(100f)) {
            anchor = loc
            anchorSince = now
        }
    }

    private fun isStationary(): Boolean =
        anchor != null && System.currentTimeMillis() - anchorSince >= STATIONARY_AFTER_MS

    fun locationIntervalMs(): Long = when (_mode.value) {
        Mode.NORMAL -> TimeUnit.MINUTES.toMillis(5)
        Mode.STATIONARY -> TimeUnit.MINUTES.toMillis(15)
        Mode.LOW_BATTERY -> TimeUnit.MINUTES.toMillis(30)
    }

    fun statusFallbackMs(): Long = when (_mode.value) {
        Mode.NORMAL -> TimeUnit.MINUTES.toMillis(2)
        Mode.STATIONARY -> TimeUnit.MINUTES.toMillis(4)
        Mode.LOW_BATTERY -> TimeUnit.MINUTES.toMillis(10)
    }

    fun statusScanMs(): Long = when (_mode.value) {
        Mode.LOW_BATTERY -> TimeUnit.SECONDS.toMillis(30)
        else -> TimeUnit.SECONDS.toMillis(10)
    }

    fun healthIntervalMs(): Long = when (_mode.value) {
        Mode.LOW_BATTERY -> TimeUnit.MINUTES.toMillis(30)
        else -> TimeUnit.MINUTES.toMillis(10)
    }

    companion object {
        private const val TAG = "PowerPolicy"
        const val LOW_BATTERY_THRESHOLD = 20
        const val STATIONARY_RADIUS_M = 80f
        val STATIONARY_AFTER_MS: Long = TimeUnit.MINUTES.toMillis(20)
    }
}
