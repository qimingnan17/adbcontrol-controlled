package com.adbcontrol.controlled.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.WsMessage
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.ExecutionResult
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.executor.AccessibilityExecutor
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.executor.ShizukuExecutor
import com.adbcontrol.controlled.storage.R2StorageClient
import com.adbcontrol.controlled.apptime.AppTimeController
import com.adbcontrol.controlled.notification.ReminderNotificationCenter
import com.adbcontrol.controlled.update.UpdateRunner
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
import java.io.ByteArrayOutputStream

/**
 * MQTT 消息处理器。README 4 / 7.2.1。
 *
 * - 收到 COMMAND 后经 [CommandDispatcher] 执行
 * - QoS 1 + LRU 去重防重发(README 7.2.1 三道防线 L1)
 * - 执行结果回执到 result/{deviceId}
 * - screencap 特判:采集 PNG → 优先 R2 上传回传 URL,无 R2 时降级压缩 JPEG base64 走 MQTT
 */
class CommandHandler(
    private val dispatcher: CommandDispatcher,
    private val mqttManager: MqttManager,
    private val json: Json,
    private val appTimeController: AppTimeController,
    private val notificationCenter: ReminderNotificationCenter,
    private val configStore: ConfigStore,
    private val shizukuExecutor: ShizukuExecutor,
    private val accessibilityExecutor: AccessibilityExecutor,
    private val updateRunner: UpdateRunner,
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
            // APP_TIME 配置类命令不下 executor 链,直接落到本地 AppTimeController;
            // screencap 特判走截图→R2 链路(原 dispatcher 只回字节大小,URL 从未回传)
            val result = handleAppTimeConfig(message.id, command)
                ?: handleScreencap(message.id, command)
                ?: dispatcher.dispatch(command, message.id)
            val duration = System.currentTimeMillis() - started
            val finalResult = if (result.durationMs == 0L) result.copy(durationMs = duration) else result
            publishResult(finalResult, message.id)
        }
    }

    /**
     * 截图闭环:
     * 1) Shizuku `screencap -p`(stdout 字节)优先,失败退无障碍 takeScreenshot;
     * 2) R2 配置且 publicRead 时上传 screenshots/{deviceId}/{ts}.png,result 回传
     *    `screenshotUrl=<url>`(Web 端直接展示/打开);
     * 3) 无可用 R2 时降级:缩到宽 ≤720 的 JPEG(base64),result 回传
     *    `screenshotBase64=<data>`(EMQX 单消息 1MB 上限内);
     * 4) 都不可用返回失败。返回 null 表示非截图命令,继续走 executor 链。
     */
    private suspend fun handleScreencap(commandId: String, command: Command): ExecutionResult? {
        if (command.category != CommandCategory.APP || command.action != "screencap") return null
        val started = System.currentTimeMillis()

        var png: ByteArray? = null
        if (shizukuExecutor.isAvailable()) {
            png = shizukuExecutor.captureScreenBytes()
        }
        if (png == null && accessibilityExecutor.isAvailable()) {
            png = runCatching { accessibilityExecutor.captureScreenPng() }
                .onFailure { Log.w(TAG, "accessibility capture failed", it) }
                .getOrNull()
        }
        if (png == null) return failResult(commandId, "SCREENSHOT_UNAVAILABLE")

        val cfg = runCatching { configStore.load() }.getOrNull()
        val r2cfg = cfg?.r2
        if (r2cfg != null && r2cfg.publicRead) {
            val key = "screenshots/${cfg.deviceId}/${started}.png"
            runCatching {
                R2StorageClient(r2cfg).upload(key, png, "image/png")
            }.onSuccess { url ->
                return ExecutionResult(
                    commandId = commandId, success = true,
                    output = "screenshotUrl=$url",
                    durationMs = System.currentTimeMillis() - started,
                )
            }.onFailure { Log.w(TAG, "R2 upload failed, fallback to base64", it) }
        }

        val small = downscaleJpeg(png, maxDimen = 720, quality = 70)
        val b64 = Base64.encodeToString(small, Base64.NO_WRAP)
        // envelope JSON + base64 需控制在 EMQX Serverless 1MB 内
        return if (b64.length <= 600_000) {
            ExecutionResult(
                commandId = commandId, success = true,
                output = "screenshotBase64=$b64",
                durationMs = System.currentTimeMillis() - started,
            )
        } else {
            failResult(commandId, "SCREENSHOT_TOO_LARGE(${png.size}B, no usable R2)")
        }
    }

    /** 解码 PNG → 等比缩放到最大边 [maxDimen] → JPEG 压缩。 */
    private fun downscaleJpeg(png: ByteArray, maxDimen: Int, quality: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: return png
        val scale = maxDimen.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1)
        val w = (src.width * scale).toInt().coerceIn(1, src.width)
        val h = (src.height * scale).toInt().coerceIn(1, src.height)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        return try {
            ByteArrayOutputStream().use { baos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                baos.toByteArray()
            }
        } finally {
            if (scaled !== src) runCatching { scaled.recycle() }
            runCatching { src.recycle() }
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
        // 后端发布新版本时经 push/{deviceId} 广播 {"event":"update_available",...},
        // 触发一次完整的 检查→下载→静默安装→上报 流程(UpdateRunner 内部防重入)
        val event = runCatching {
            json.decodeFromString(JsonObject.serializer(), message.payload)
        }.getOrNull()?.get("event")?.toString()?.trim('"')
        if (event == "update_available") {
            updateRunner.trigger("push")
        }
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
