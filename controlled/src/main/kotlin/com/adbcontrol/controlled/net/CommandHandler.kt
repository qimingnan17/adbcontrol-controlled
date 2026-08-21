package com.adbcontrol.controlled.net

import android.util.Log
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.WsMessage
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.ExecutionResult
import com.adbcontrol.controlled.executor.CommandDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MQTT 消息处理器。README 4 / 7.2.1。
 *
 * - 收到 COMMAND 后经 [CommandDispatcher] 执行
 * - QoS 1 + LRU 去重防重发(README 7.2.1 三道防线 L1)
 * - 执行结果回执到 result/{deviceId}
 */
class CommandHandler(
    private val dispatcher: CommandDispatcher,
    private val mqttManager: MqttManager,
    private val json: Json,
) : MqttManager.MqttMessageListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processedIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    override fun onCommand(message: WsMessage, command: Command) {
        // L1 LRU 去重:重复 msg_id 直接跳过
        if (!processedIds.add(message.id)) {
            Log.i(TAG, "duplicate command ${message.id}, skip")
            return
        }
        trimProcessed()
        scope.launch {
            val started = System.currentTimeMillis()
            val result = dispatcher.dispatch(command, message.id)
            val duration = System.currentTimeMillis() - started
            val finalResult = if (result.durationMs == 0L) result.copy(durationMs = duration) else result
            publishResult(finalResult, message.id)
        }
    }

    private fun publishResult(result: ExecutionResult, originalId: String) {
        val deviceId = mqttManager.currentDeviceId() ?: return
        // ExecutionResult 在 shared 模块未声明 @Serializable,这里手写 JSON 对象。
        val element: JsonObject = buildJsonObject {
            put("commandId", result.commandId)
            put("success", result.success)
            put("output", result.output)
            put("durationMs", result.durationMs)
            put("timestamp", result.timestamp)
        }
        val payloadJson = json.encodeToString(JsonObject.serializer(), element)
        val msg = WsMessage(
            id = "result-$originalId",
            type = MessageType.COMMAND_RESULT,
            payload = payloadJson,
            timestamp = System.currentTimeMillis(),
        )
        mqttManager.publish(msg, "result/$deviceId", qos = 1)
    }

    private fun trimProcessed() {
        if (processedIds.size > 5000) {
            val iter = processedIds.iterator()
            repeat(1000) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
    }

    override fun onReminder(message: WsMessage) {
        Log.i(TAG, "reminder received: ${message.id}")
    }

    override fun onPush(message: WsMessage) {
        Log.i(TAG, "push data received: ${message.id}")
    }

    override fun onPing(message: WsMessage) {
        // pong 由 MqttManager 直接回复
    }

    override fun onUpdateNotify(message: WsMessage) {
        Log.i(TAG, "update notify received: ${message.id}")
        // 触发更新检查(由 ControlledService 桥接到 UpdateChannel)
    }

    override fun onControllerOffline(controllerDeviceId: String) {
        Log.w(TAG, "controller offline: $controllerDeviceId")
    }

    override fun onConnected() {
        Log.i(TAG, "mqtt connected")
    }

    override fun onDisconnected(cause: Throwable?) {
        Log.w(TAG, "mqtt disconnected: ${cause?.message}")
    }

    companion object {
        private const val TAG = "CommandHandler"
    }
}
