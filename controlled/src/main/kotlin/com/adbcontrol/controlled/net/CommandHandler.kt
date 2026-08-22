package com.adbcontrol.controlled.net

import android.util.Log
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.WsMessage
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.ExecutionResult
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.apptime.AppTimeController
import com.adbcontrol.controlled.notification.ReminderNotificationCenter
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.model.ReminderPayload
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
    private val appTimeController: AppTimeController,
    private val notificationCenter: ReminderNotificationCenter,
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
            // APP_TIME 配置类命令不下 executor 链,直接落到本地 AppTimeController
            val result = handleAppTimeConfig(message.id, command)
                ?: dispatcher.dispatch(command, message.id)
            val duration = System.currentTimeMillis() - started
            val finalResult = if (result.durationMs == 0L) result.copy(durationMs = duration) else result
            publishResult(finalResult, message.id)
        }
    }

    /**
     * 处理应用时间管控配置(setLimit / clearLimit / setWindow / clearWindow)。
     * 返回 null 表示该命令仍需走 executor 链(如 suspend/unsuspend/hide)。
     */
    private fun handleAppTimeConfig(commandId: String, command: Command): ExecutionResult? {
        if (command.category != CommandCategory.APP_TIME) return null
        val pkg = command.params["pkg"]?.trim().orEmpty()
        return when (command.action) {
            "setLimit" -> {
                if (pkg.isEmpty()) return failResult(commandId, "missing pkg")
                val minutes = command.params["minutes"]?.toIntOrNull() ?: 0
                if (minutes <= 0) {
                    appTimeController.clearLimit(pkg)
                    ExecutionResult(commandId = commandId, success = true, output = "limit cleared")
                } else {
                    appTimeController.setLimit(pkg, minutes)
                    ExecutionResult(commandId = commandId, success = true, output = "limit set: $minutes min")
                }
            }
            "clearLimit" -> {
                if (pkg.isEmpty()) return failResult(commandId, "missing pkg")
                appTimeController.clearLimit(pkg)
                ExecutionResult(commandId = commandId, success = true, output = "limit cleared")
            }
            "setWindow" -> {
                if (pkg.isEmpty()) return failResult(commandId, "missing pkg")
                val start = command.params["start"].orEmpty()
                val end = command.params["end"].orEmpty()
                if (start.isEmpty() || end.isEmpty()) {
                    appTimeController.clearWindow(pkg)
                    ExecutionResult(commandId = commandId, success = true, output = "window cleared")
                } else {
                    appTimeController.setWindow(pkg, start, end)
                    ExecutionResult(commandId = commandId, success = true, output = "window set: $start-$end")
                }
            }
            "clearWindow" -> {
                if (pkg.isEmpty()) return failResult(commandId, "missing pkg")
                appTimeController.clearWindow(pkg)
                ExecutionResult(commandId = commandId, success = true, output = "window cleared")
            }
            else -> null
        }
    }

    private fun failResult(commandId: String, error: String): ExecutionResult =
        ExecutionResult(commandId = commandId, success = false, output = error)

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
        // 解析载荷并弹通知;解析失败全静默丢弃(防错配置导致崩溃)
        val payload = runCatching {
            json.decodeFromString(ReminderPayload.serializer(), message.payload)
        }.getOrElse {
            Log.e(TAG, "reminder payload decode failed: ${message.payload}")
            return
        }
        notificationCenter.show(message.id, payload)
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
