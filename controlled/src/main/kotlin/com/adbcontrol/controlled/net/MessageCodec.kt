package com.adbcontrol.controlled.net

import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.ProtocolVersion
import com.adbcontrol.shared.WsMessage
import com.adbcontrol.shared.security.HmacSigner
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MqttEnvelope(
    val id: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    val signature: String? = null,
    /** v2.0 契约 §1:发送恒为 2;接收缺失视为 1(见 [ProtocolVersion.detect])。不参与签名,兼容 v1 验签。 */
    @EncodeDefault val protocolVersion: Int = ProtocolVersion.CURRENT,
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
            protocolVersion = ProtocolVersion.CURRENT,
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
        val raw = String(bytes, Charsets.UTF_8)
        val envelope = runCatching {
            json.decodeFromString(MqttEnvelope.serializer(), raw)
        }.getOrElse {
            // 排障可观测:此前静默返回 null,链路故障(如主控端 payload 字段错发 Base64)无从定位。
            // 只记原因,不落 payload/密钥内容。
            android.util.Log.w("MessageCodec", "drop: envelope parse failed err=${it.message}")
            return null
        }

        if (sessionKeyBase64 != null) {
            val sig = envelope.signature
            // 配对后必带签名,缺失即拒绝(攻击者删 signature 字段无法绕过)
            if (sig.isNullOrEmpty()) {
                android.util.Log.w("MessageCodec", "drop: missing signature (id=${envelope.id})")
                return null
            }
            val signingData = HmacSigner.buildSigningData(envelope.payload, envelope.id, envelope.timestamp)
            if (!HmacSigner.verify(signingData, sessionKeyBase64, sig)) {
                android.util.Log.w("MessageCodec", "drop: hmac verify failed (id=${envelope.id})")
                return null
            }
            // 重放窗口 5 分钟
            if (!HmacSigner.isWithinReplayWindow(envelope.timestamp)) {
                android.util.Log.w("MessageCodec", "drop: replay window exceeded (id=${envelope.id}, ts=${envelope.timestamp})")
                return null
            }
        }
        // 协议版本:缺失字段 = v1(旧后端/旧主控),记录并容忍;只有不可解析才在上面被拒
        val version = ProtocolVersion.detect(raw) ?: ProtocolVersion.LEGACY
        if (ProtocolVersion.isOlder(version)) {
            android.util.Log.i("MessageCodec", "legacy envelope v$version tolerated (id=${envelope.id}, type=${envelope.type})")
        }
        val type = parseType(envelope.type)
        return WsMessage(
            id = envelope.id,
            type = type,
            payload = envelope.payload,
            timestamp = envelope.timestamp,
            protocolVersion = version,
        )
    }

    /** 信封 type 字符串 → 枚举;大小写/中划线容错,未知类型兜底 PUSH_DATA(由 topic 路由)。 */
    private fun parseType(raw: String): MessageType {
        val norm = raw.trim().uppercase().replace('-', '_')
        return runCatching { MessageType.valueOf(norm) }.getOrDefault(MessageType.PUSH_DATA)
    }
}
