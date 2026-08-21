package com.adbcontrol.controlled.telemetry

import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.adbcontrol.shared.model.StatusReport

/**
 * 系统信息采集工具。README 第五章遥测采集的共享读取逻辑。
 */
class SystemInfoCollector(private val context: Context) {

    fun batteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    fun network(): Pair<StatusReport.NetworkType, Int> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return StatusReport.NetworkType.NONE to 0
        }
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                StatusReport.NetworkType.WIFI to wifiRssi()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                StatusReport.NetworkType.CELLULAR_4G to cellRssi()
            else -> StatusReport.NetworkType.NONE to 0
        }
    }

    private fun wifiRssi(): Int = -50 // TODO: 通过 WifiManager connectionInfo.rssi 取真实值
    private fun cellRssi(): Int = -80 // TODO: 通过 TelephonyManager signalStrength 取真实值

    fun isScreenOn(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /** 当前前台包名:优先 AccessibilityService,其次 UsageStatsManager。 */
    fun foregroundPackage(): String? {
        // AccessibilityServiceBridge 更实时;UsageStatsManager 兜底
        com.adbcontrol.controlled.accessibility.AccessibilityServiceBridge.get()?.let { service ->
            service.rootInActiveWindow?.packageName?.let { return it.toString() }
        }
        return usageStatsForeground()
    }

    private fun usageStatsForeground(): String? {
        if (!hasUsageStatsPermission()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 60_000, now)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    fun hasUsageStatsPermission(): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 60_000, now)
        return stats != null && stats.isNotEmpty()
    }

    val androidVersion: String = Build.VERSION.RELEASE
    val sdkInt: Int = Build.VERSION.SDK_INT
}
