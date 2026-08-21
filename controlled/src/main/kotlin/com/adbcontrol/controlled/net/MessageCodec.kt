package com.adbcontrol.controlled.net

import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.WsMessage
import com.adbcontrol.shared.security.HmacSigner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MQTT 线缆信封(README 4.2 / 8.2)。
 *
 * wire 格式:`{id, type, payload, timestamp, signature}`,与主控端
 * [com.adbcontrol.controller.net.IncomingEnvelope] 完全对齐,两端互验互通。
 *
 * shared 模块的 [WsMessage] 不含 signature 字段(保持协议纯净),HMAC 签名外挂到此封装。
 * 设计参见 README 8.2:
 * `signature = HMAC-SHA256(payload + ":" + id + ":" + timestamp, sessionKey)`
 *
 * `type` 在 wire 上为字符串(主控端按字符串解析后按 topic 路由),被控端发布时由
 * [WsMessage.type] 序列化得到;接收时由 [decode] 还原为 [MessageType],未知类型(遥测/
 * 配对/更新)在 shared 中未声明时,落入 [MessageType.PUSH_DATA] 兜底(由 topic 路由)。
 */
@Serializable
data class MqttEnvelope(
    val id: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    val signature: String? = null,
)

/**
 * 消息编解码 + 签名/验签工具。README 8.2 三防:伪造/重放/越权。
 */
class MessageCodec(
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    fun encode(message: WsMessage, sessionKeyBase64: String?): ByteArray {
        val signature = if (sessionKeyBase64.isNullOrEmpty()) null
        else HmacSigner.sign(
            HmacSigner.buildSigningData(message.payload, message.id, message.timestamp),
            sessionKeyBase64,
        )
        val envelope = MqttEnvelope(
            id = message.id,
            type = message.type.name,
            payload = message.payload,
            timestamp = message.timestamp,
            signature = signature,
        )
        return json.encodeToString(MqttEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    /**
     * 解码并验签。
     * @return 解出的 [WsMessage],验签失败或重放窗口外返回 null。
     *
     * 配对后(sessionKey != null)所有消息必须带签名,缺失签名直接拒绝(防伪造)。
     * 配对前(sessionKey == null)宽松放行,仅做反序列化。
     */
    fun decode(bytes: ByteArray, sessionKeyBase64: String?): WsMessage? {
        val envelope = runCatching {
            json.decodeFromString(MqttEnvelope.serializer(), String(bytes, Charsets.UTF_8))
        }.getOrElse { return null }

        if (sessionKeyBase64 != null) {
            val sig = envelope.signature
            // 配对后必带签名,缺失即拒绝(攻击者删 signature 字段无法绕过)
            if (sig.isNullOrEmpty()) return null
            val signingData = HmacSigner.buildSigningData(envelope.payload, envelope.id, envelope.timestamp)
            if (!HmacSigner.verify(signingData, sessionKeyBase64, sig)) return null
            // 重放窗口 5 分钟
            if (!HmacSigner.isWithinReplayWindow(envelope.timestamp)) return null
        }
        val type = runCatching { MessageType.valueOf(envelope.type) }.getOrDefault(MessageType.PUSH_DATA)
        return WsMessage(
            id = envelope.id,
            type = type,
            payload = envelope.payload,
            timestamp = envelope.timestamp,
        )
    }
}
