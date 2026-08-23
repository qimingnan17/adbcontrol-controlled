package com.adbcontrol.controlled.net

import android.util.Log
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.WsMessage
import com.adbcontrol.shared.model.AppConfig
import com.adbcontrol.shared.model.Command
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File
import javax.net.ssl.SSLSocketFactory

/**
 * MQTT 客户端管理。README 第四章。
 *
 * - TLS 8883 连接 EMQX Cloud Serverless
 * - cleanSession=false(QoS 1 离线消息不丢)
 * - LWT: device/offline/{deviceId},Agent 被杀秒级感知
 * - 自动重连 maxReconnectDelay=30s
 * - 订阅 5 个 topic:cmd/reminder/push/ping/controller-offline
 * - 收到 COMMAND 后 dispatch 到 [listener]
 */
class MqttManager(
    private val context: android.content.Context,
    private val codec: MessageCodec,
    private val json: Json,
) {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var client: MqttAsyncClient? = null
    private var config: AppConfig? = null

    /**
     * 息屏保活锁:手机长时间息屏时,国产 ROM/Doze 会挂起 CPU 导致 Paho keepalive
     * 心跳发不出去而被 broker 判死。连接期间持有 partial WakeLock(CPU 亮、屏幕灭),
     * 配合前台服务把断联概率压到最低。带超时上限防异常路径泄漏,重连成功时续期。
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireKeepAliveLock() {
        runCatching {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.takeIf { !it.isHeld }?.acquire(WAKELOCK_TIMEOUT_MS)
        }.onFailure { Log.w(TAG, "acquire wake lock failed", it) }
    }

    private fun releaseKeepAliveLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    /** 当前设备 ID(配对后可用)。 */
    fun currentDeviceId(): String? = config?.deviceId

    var listener: MqttMessageListener? = null

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    /** 是否已就绪(配置 + 已连接)。 */
    fun isReady(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    /** 初始化并连接。 */
    fun start(config: AppConfig) {
        this.config = config
        connectInternal(config)
    }

    private fun connectInternal(config: AppConfig) {
        runCatching {
            // 已连接相同 host 直接返回;否则断开旧连接重建,避免 config 指向新 broker 而 client 仍连旧 broker
            if (client?.isConnected == true && this.config?.broker?.host == config.broker.host) return
            if (client != null) stop()

            val broker = config.broker
            val serverUri = if (broker.useTls) {
                "ssl://${broker.host}:${broker.port}"
            } else {
                "tcp://${broker.host}:${broker.port}"
            }
            val persistence = MqttDefaultFilePersistence(
                File(context.filesDir, "mqtt-persistence").absolutePath
            )
            val mqttClient = MqttAsyncClient(
                serverUri,
                "device-${config.deviceId}",
                persistence,
            )
            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "connectComplete reconnect=$reconnect uri=$serverURI")
                    // 连接建立(含自动重连)即持有息屏保活锁;重连成功相当于续期
                    acquireKeepAliveLock()
                    // 订阅成功后才置 CONNECTED;订阅前用 RECONNECTING/CONNECTING 占位
                    if (reconnect) _connectionState.value = ConnectionState.RECONNECTING
                    subscribeTopics(mqttClient, config.deviceId)
                    _connectionState.value = ConnectionState.CONNECTED
                    listener?.onConnected()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "connectionLost: ${cause?.message}")
                    // 认证失败(原因码 4/5):凭证被吊销(如 Web 端删设备/吊销)或过期,
                    // 自动重连只会反复 401,直接上抛让上层清理本地配对。
                    if (isAuthFailure(cause)) {
                        listener?.onAuthFailed()
                        return
                    }
                    // automaticReconnect=true,Paho 会自动重连,置 RECONNECTING 而非 DISCONNECTED(避免 UI 误判为永久掉线)
                    _connectionState.value = ConnectionState.RECONNECTING
                    listener?.onDisconnected(cause)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) return
                    handleMessage(topic, message, config)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                maxReconnectDelay = 30_000
                isCleanSession = broker.cleanSession
                keepAliveInterval = broker.keepAliveSec
                connectionTimeout = 30
                userName = broker.username
                password = broker.password.toCharArray()
                if (broker.useTls) {
                    socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                }
                // LWT: device/offline/{deviceId} QoS 1,载荷裸 deviceId(主控按 topic 路由识别)
                val willTopic = "device/offline/${config.deviceId}"
                setWill(
                    willTopic,
                    config.deviceId.toByteArray(Charsets.UTF_8),
                    1,
                    true,
                )
            }

            _connectionState.value = ConnectionState.CONNECTING
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "connect success")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "connect failure", exception)
                    _connectionState.value = ConnectionState.FAILED
                    listener?.onDisconnected(exception)
                }
            })
            client = mqttClient
        }.onFailure {
            Log.e(TAG, "start failed", it)
            _connectionState.value = ConnectionState.FAILED
        }
    }

    private fun subscribeTopics(client: MqttAsyncClient, deviceId: String) {
        val topics = arrayOf(
            "cmd/$deviceId",          // QoS 1
            "reminder/$deviceId",     // QoS 1
            "push/$deviceId",         // QoS 1
            "ping/$deviceId",         // QoS 0
            "controller/offline/+",   // QoS 1 主控掉线
        )
        val qos = intArrayOf(1, 1, 1, 0, 1)
        runCatching {
            client.subscribe(topics, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { Log.i(TAG, "subscribe ok") }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) { Log.e(TAG, "subscribe failed", exception) }
            })
        }.onFailure { Log.e(TAG, "subscribe failed", it) }
    }

    private fun handleMessage(topic: String, message: MqttMessage, config: AppConfig) {
        // LWT 离线报文:主控端 LWT payload 是裸 controllerId,不经过 envelope codec。
        // 拦截在 codec.decode 之前,否则裸字符串当 JSON 解析失败被丢弃,离线检测失效。
        if (topic.startsWith("controller/offline/")) {
            val controllerId = String(message.payload, Charsets.UTF_8).trim()
            listener?.onControllerOffline(controllerId)
            return
        }

        val ws = codec.decode(message.payload, config.sessionKey) ?: run {
            Log.w(TAG, "decode/verify failed, drop: $topic")
            return
        }
        when (ws.type) {
            MessageType.COMMAND -> {
                val cmd = runCatching {
                    json.decodeFromString(Command.serializer(), ws.payload)
                }.getOrElse {
                    Log.e(TAG, "command decode failed: ${ws.payload}")
                    return
                }
                listener?.onCommand(ws, cmd)
            }
            MessageType.REMINDER -> listener?.onReminder(ws)
            MessageType.PING -> {
                // 回 PONG(配对前 sessionKey 可能为 null,codec.encode 已处理为不带签名)
                val pong = WsMessage(
                    id = "pong-${ws.id}",
                    type = MessageType.PONG,
                    payload = config.deviceId,
                    timestamp = System.currentTimeMillis(),
                )
                publish(pong, "pong/${config.deviceId}", qos = 0)
                listener?.onPing(ws)
            }
            MessageType.PUSH_DATA -> {
                // 推送通道:同时承载遥测回执、更新通知等业务事件(主控端按 payload 内 event 字段分发)
                listener?.onPush(ws)
                listener?.onUpdateNotify(ws)
            }
            else -> Unit
        }
    }

    /** 发布一条 WsMessage 到指定 topic。sessionKey 可为 null(配对前),codec 置 signature=null。 */
    fun publish(message: WsMessage, topic: String, qos: Int = 1): Boolean {
        val cfg = config ?: return false
        val mqtt = client ?: return false
        repeat(3) { attempt ->
            val result = runCatching {
                val payload = codec.encode(message, cfg.sessionKey)
                val m = MqttMessage(payload).apply { this.qos = qos }
                mqtt.publish(topic, m)
                true
            }
            if (result.isSuccess) return true
            Log.w(TAG, "publish attempt ${attempt + 1} failed to $topic", result.exceptionOrNull())
            if (attempt < 2) Thread.sleep(200)
        }
        Log.e(TAG, "publish finally failed to $topic after 3 attempts")
        return false
    }

    /** 发布原始遥测 payload(已序列化)到 topic,封装为 WsMessage。 */
    fun publishTelemetry(type: MessageType, payloadJson: String, topic: String, qos: Int): Boolean {
        val cfg = config ?: return false
        val msg = WsMessage(
            id = "tm-${type.name.lowercase()}-${System.nanoTime()}",
            type = type,
            payload = payloadJson,
            timestamp = System.currentTimeMillis(),
        )
        return publish(msg, topic, qos)
    }

    /** 停止并断开。 */
    fun stop() {
        releaseKeepAliveLock()
        runCatching {
            client?.disconnectForcibly()
            client?.close()
        }
        client = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** 是否认证失败:凭证被吊销/过期。原因码 4=REASON_FAILED_AUTHENTICATION,5=REASON_NOT_AUTHORIZED。 */
    private fun isAuthFailure(cause: Throwable?): Boolean =
        cause is org.eclipse.paho.client.mqttv3.MqttException &&
            (cause.reasonCode == 4 || cause.reasonCode == 5)

    interface MqttMessageListener {
        fun onCommand(message: WsMessage, command: Command)
        fun onReminder(message: WsMessage)
        fun onPush(message: WsMessage)
        fun onPing(message: WsMessage)
        fun onUpdateNotify(message: WsMessage)
        fun onControllerOffline(controllerDeviceId: String)
        fun onConnected()
        fun onDisconnected(cause: Throwable?)
        /** 连接被认证拒绝(凭证吊销/过期):上层应清除本地配对并停 MQTT,避免反复 401 重连。 */
        fun onAuthFailed() {}
    }

    companion object {
        private const val TAG = "MqttManager"
        private const val WAKELOCK_TAG = "adbcontrol:mqtt_keepalive"

        /** 保活锁单次持有时限:6 小时,connectComplete(重连)时会重新 acquire 续期。 */
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
