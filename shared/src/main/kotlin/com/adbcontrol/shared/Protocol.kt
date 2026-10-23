package com.adbcontrol.shared

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * v2.0 协议版本(跨仓库契约 §1)。
 *
 * - 所有 MQTT 信封 / 协议模型都带 `protocolVersion`,发送端始终写 [CURRENT](@EncodeDefault 保证必出现在 JSON 中)。
 * - 接收端:字段缺失 = [LEGACY](v1 旧端);旧版本仅记日志并容忍,只有 JSON 不可解析才拒绝。
 *
 * 注意 kotlinx 默认值会把"缺失"解成 2,因此需要区分新旧端时用 [detect] 读原始 JSON。
 */
object ProtocolVersion {
    const val CURRENT: Int = 2
    const val LEGACY: Int = 1

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 读取原始 JSON 对象中的 protocolVersion;缺失返回 [LEGACY],不可解析返回 null。 */
    fun detect(rawJson: String): Int? = runCatching {
        val obj = lenient.parseToJsonElement(rawJson) as? JsonObject ?: return null
        obj["protocolVersion"]?.jsonPrimitive?.intOrNull ?: LEGACY
    }.getOrNull()

    /** 是否为比本端更旧的版本(需要兼容处理 + 日志)。 */
    fun isOlder(version: Int): Boolean = version < CURRENT
}

/**
 * WebSocket 消息协议。主控端 <-> 被控端之间的所有通信都封装为 [WsMessage]，
 * 通过 type 字段区分种类，使用 JSON 序列化传输。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WsMessage(
    val id: String,                 // 消息唯一ID（UUID），用于配对请求/回报
    val type: MessageType,
    val payload: String,            // 序列化后的载荷 JSON
    val timestamp: Long = System.currentTimeMillis(),
    @EncodeDefault val protocolVersion: Int = ProtocolVersion.CURRENT,
)

@Serializable
enum class MessageType {
    PING,                           // 心跳
    PONG,
    COMMAND,                        // 主控端 -> 被控端：执行一条命令
    COMMAND_RESULT,                 // 被控端 -> 主控端：命令回报（可选）
    REMINDER,                       // 主控端 -> 被控端：触发提醒
    REMINDER_RESULT,                // 被控端 -> 主控端：提醒回报（可选）
    PUSH_DATA,                      // 主控端 -> 被控端：数据推送
    ACK,                            // 通用确认
    ERROR,                          // 通用错误

    // ---------- v2.0(契约 §3 / §5) ----------
    EXTENSION_REQUEST,              // 被控端 -> 后端:申请延长某 App 当日可用时长(request/{deviceId})
    APP_TIME_EXTEND,                // 后端 -> 被控端:延时申请已批准(cmd/{deviceId})
    APP_TIME_EXTEND_DENIED,         // 后端 -> 被控端:延时申请被拒绝(cmd/{deviceId})
    GEOFENCE_SET,                   // 后端 -> 被控端:下发地理围栏全集(cmd/{deviceId})
}
