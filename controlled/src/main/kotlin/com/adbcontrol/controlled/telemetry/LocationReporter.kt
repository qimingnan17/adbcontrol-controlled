package com.adbcontrol.controlled.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.LocationReport
import kotlinx.serialization.json.Json

/**
 * GPS 位置回报。README 5.3。
 *
 * - LocationManager GPS + Network 双 provider
 * - 默认 15 分钟周期,屏幕关闭延长至 30 分钟(低功耗)
 * - 围栏事件触发(enter/leave),QoS 1
 * - 定位失败回退 network provider
 */
@SuppressLint("MissingPermission")
class LocationReporter(
    private val context: Context,
    private val mqttManager: MqttManager,
    private val json: Json,
) {

    private val lm: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /** 最近一次上报位置,用于围栏判定。 */
    @Volatile private var lastLat: Double = 0.0
    @Volatile private var lastLng: Double = 0.0

    /** 围栏列表(lat,lng,radius,label)。主控下发,存内存(后续接入 Room fence 表)。 */
    private val fences = mutableListOf<Geofence>()

    data class Geofence(val lat: Double, val lng: Double, val radiusMeters: Float, val label: String)

    fun setFences(fences: List<Geofence>) {
        synchronized(this.fences) {
            this.fences.clear()
            this.fences.addAll(fences)
        }
    }

    /** 周期上报:取最近一次已知位置(被动),发 QoS 0。 */
    fun reportOnce(deviceId: String): Boolean {
        val loc = lastKnownLocation() ?: return false
        return publish(deviceId, loc, provider = loc.provider ?: "unknown", fenceEvent = null, qos = 0)
    }

    private fun publish(
        deviceId: String,
        loc: Location,
        provider: String,
        fenceEvent: String?,
        qos: Int,
    ): Boolean {
        val report = LocationReport(
            deviceId = deviceId,
            lat = loc.latitude,
            lng = loc.longitude,
            accuracy = loc.accuracy,
            speed = if (loc.hasSpeed()) loc.speed else 0f,
            provider = provider ?: "unknown",
            fenceEvent = fenceEvent,
            timestamp = System.currentTimeMillis(),
        )
        val payload = json.encodeToString(LocationReport.serializer(), report)
        return mqttManager.publishTelemetry(
            MessageType.PUSH_DATA, payload, "location/$deviceId", qos = qos
        ).also {
            lastLat = loc.latitude
            lastLng = loc.longitude
        }
    }

    private fun lastKnownLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            if (lm.isProviderEnabled(p)) {
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    /**
     * 启动主动定位(GPS 优先,失败回退 network)。
     * 屏幕关闭时调用方可改为 30 分钟被动采样。
     */
    fun startActive(onLocation: (Location) -> Unit) {
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            LocationManager.NETWORK_PROVIDER
        } else return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                    if (loc != null) onLocation(loc)
                }
            } else {
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) { onLocation(location) }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Suppress("DEPRECATION")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }, null)
            }
        }.onFailure { Log.w(TAG, "startActive failed", it) }
    }

    /** 判定围栏事件(enter/leave)。返回事件名或 null。 */
    fun checkFence(lat: Double, lng: Double): String? {
        synchronized(fences) {
            for (f in fences) {
                val results = FloatArray(1)
                Location.distanceBetween(lat, lng, f.lat, f.lng, results)
                val inside = results[0] <= f.radiusMeters
                val wasInside = lastLat != 0.0 && run {
                    val r = FloatArray(1)
                    Location.distanceBetween(lastLat, lastLng, f.lat, f.lng, r)
                    r[0] <= f.radiusMeters
                }
                return when {
                    inside && !wasInside -> "enter:${f.label}"
                    !inside && wasInside -> "leave:${f.label}"
                    else -> null
                }
            }
        }
        return null
    }

    companion object { private const val TAG = "LocationReporter" }
}
