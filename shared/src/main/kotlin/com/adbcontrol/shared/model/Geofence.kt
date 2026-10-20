package com.adbcontrol.shared.model

import com.adbcontrol.shared.ProtocolVersion
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * v2.0 契约 §5:地理围栏。`PUT /api/devices/{id}/geofences` 后后端经 cmd/{deviceId}
 * 下发 GEOFENCE_SET(全量替换)。
 */
@Serializable
data class GeofenceDef(
    val id: Long = 0,
    val name: String = "",
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
)

/** GEOFENCE_SET 载荷(对象形态)。被控端同时兼容裸数组 `[GeofenceDef...]`。 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GeofenceSet(
    val geofences: List<GeofenceDef> = emptyList(),
    @EncodeDefault val protocolVersion: Int = ProtocolVersion.CURRENT,
)
