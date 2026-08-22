package com.adbcontrol.controlled.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adbcontrol.controlled.R
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.controlled.ui.MainActivity
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.WsMessage
import com.adbcontrol.shared.model.ReminderAck
import com.adbcontrol.shared.model.ReminderPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主控端下发的任务栏通知中心。
 *
 * - 独立于前台常驻通知的渠道([CHANNEL_REMOTE_TASK],IMPORTANCE_HIGH,
 *   可响铃/横幅,体现"重要提醒"语义)
 * - [ReminderPayload.buttons] 每条渲染为一个通知操作按钮,文字完全由主控自定义
 * - expectAck=true 时,点击按钮(或通知本体)即视为签收:取消通知并向
 *   result/{deviceId} 发布 REMINDER_RESULT([ReminderAck]);expectAck=false 时
 *   不挂任何按钮,纯展示,点击本体仅打开 App
 *
 * 按钮点击经动态注册的 BroadcastReceiver(本应用内组件,RECEIVER_NOT_EXPORTED)
 * 回到本类。receiver 在进程存活期间常驻注册(服务常驻,代价可忽略)。
 */
@Singleton
class ReminderNotificationCenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mqttManager: MqttManager,
    private val json: Json,
) {

    private val ackReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_ACK) return
            val refId = intent.getStringExtra(EXTRA_REF_ID) ?: return
            val buttonText = intent.getStringExtra(EXTRA_BUTTON_TEXT) ?: ReminderAck.OPEN_TOKEN
            val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it >= 0 }
            val notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, 0)

            // 取消对应通知(按钮点击通常系统自动 dismiss,但显式处理更稳)
            notificationManager.cancel(notifyId)

            publishAck(refId, taskId, buttonText)
        }
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        ensureChannel()
        registerReceiver()
    }

    /** 弹出一条 REMINDER 通知。envelopeId 用作幂等引用与通知 ID 派生。 */
    fun show(envelopeId: String, payload: ReminderPayload) {
        val notifyId = NOTIFICATION_BASE_ID + (envelopeId.hashCode() and 0x0FFFFFFF)

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_REMOTE_TASK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.title)
            .setContentText(payload.text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        if (payload.expectAck) {
            // 按钮签收:每条按钮文字对应一个 ACTION_ACK 广播
            // 最多渲染 2 个按钮(Android 通知气泡展示上限;超出会被系统截断)
            payload.buttons.take(2).forEachIndexed { index, label ->
                val intent = Intent(ACTION_ACK).apply {
                    putExtra(EXTRA_REF_ID, envelopeId)
                    putExtra(EXTRA_BUTTON_TEXT, label)
                    putExtra(EXTRA_TASK_ID, payload.taskId ?: -1L)
                    putExtra(EXTRA_NOTIFY_ID, notifyId)
                }
                val pi = PendingIntent.getBroadcast(
                    context, REQUEST_CODE_BASE + index, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(0, label.ifBlank { "签收" }, pi)
            }
        }

        notificationManager.notify(notifyId, builder.build())
        Log.i(TAG, "reminder shown: envelopeId=$envelopeId notifyId=$notifyId ack=${payload.expectAck}")
    }

    /** 发布签收回报。 */
    private fun publishAck(refId: String, taskId: Long?, buttonText: String) {
        val deviceId = mqttManager.currentDeviceId() ?: run {
            Log.w(TAG, "publishAck skipped: no deviceId")
            return
        }
        val ack = ReminderAck(
            refId = refId,
            taskId = taskId,
            buttonText = buttonText,
        )
        val payloadJson = json.encodeToString(ReminderAck.serializer(), ack)
        val msg = WsMessage(
            // 幂等键:由 refId + 按钮文字派生,同一按钮重复点击不会重复入库
            // (主控端 task_ack 表 UNIQUE(ack_id),重复消息幂等)
            id = "ack-$refId-${buttonText.hashCode().toUInt()}",
            type = MessageType.REMINDER_RESULT,
            payload = payloadJson,
            timestamp = System.currentTimeMillis(),
        )
        val ok = mqttManager.publish(msg, "result/$deviceId", qos = 1)
        Log.i(TAG, "ack published: refId=$refId button=$buttonText ok=$ok")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_REMOTE_TASK) != null) return
            val channel = NotificationChannel(
                CHANNEL_REMOTE_TASK,
                context.getString(R.string.notif_channel_remote_task),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_remote_task_desc)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter(ACTION_ACK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(ackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(ackReceiver, filter)
        }
    }

    companion object {
        private const val TAG = "ReminderNotifCenter"
        private const val CHANNEL_REMOTE_TASK = "remote_task"
        // 通知 action 广播 action 串(应用内私有)
        const val ACTION_ACK = "com.adbcontrol.controlled.action.REMINDER_ACK"
        const val EXTRA_REF_ID = "ref_id"
        const val EXTRA_BUTTON_TEXT = "button_text"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_NOTIFY_ID = "notify_id"
        private const val NOTIFICATION_BASE_ID = 20_000
        private const val REQUEST_CODE_BASE = 2_000
    }
}
