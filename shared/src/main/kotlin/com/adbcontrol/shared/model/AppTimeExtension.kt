package com.adbcontrol.shared.model

import com.adbcontrol.shared.ProtocolVersion
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * v2.0 契约 §3:使用时长延时申请(申请延时)。
 *
 * 被控端 → 后端:发布到 `request/{deviceId}`,信封 type = EXTENSION_REQUEST,QoS 1。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionRequest(
    /** UUID,后端 extension_request.request_id UNIQUE,幂等键 */
    val requestId: String,
    val packageName: String,
    val appLabel: String,
    val minutes: Int,
    val reason: String? = null,
    val ts: Long,
    @EncodeDefault val protocolVersion: Int = ProtocolVersion.CURRENT,
)

/** 后端 → 被控端:批准(cmd/{deviceId},type = APP_TIME_EXTEND)。 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppTimeExtend(
    val requestId: String? = null,
    val packageName: String,
    val minutes: Int,
    @EncodeDefault val protocolVersion: Int = ProtocolVersion.CURRENT,
)

/** 后端 → 被控端:拒绝(cmd/{deviceId},type = APP_TIME_EXTEND_DENIED)。 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppTimeExtendDenied(
    val requestId: String? = null,
    val packageName: String,
    @EncodeDefault val protocolVersion: Int = ProtocolVersion.CURRENT,
)
