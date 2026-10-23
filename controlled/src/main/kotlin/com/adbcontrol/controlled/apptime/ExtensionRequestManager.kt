package com.adbcontrol.controlled.apptime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adbcontrol.controlled.R
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.controlled.ui.MainActivity
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.WsMessage
import com.adbcontrol.shared.model.AppTimeExtend
import com.adbcontrol.shared.model.AppTimeExtendDenied
import com.adbcontrol.shared.model.ExtensionRequest
import com.adbcontrol.shared.net.MqttTopics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.0 契约 §3:使用时长延时申请(申请延时)。
 *
 * - [request]:发布 EXTENSION_REQUEST 到 `request/{deviceId}`(QoS 1),本地记为 PENDING
 * - [onApproved]:APP_TIME_EXTEND → 加到今日配额 + 解封 + 通知用户
 * - [onDenied]:APP_TIME_EXTEND_DENIED → 通知用户
 * - 30 分钟未决自动视为 EXPIRED(与后端自动过期一致,只影响本地展示,可重新申请)
 *
 * 状态按包名保存最近一次申请,持久化到 SharedPreferences,拦截页与首页据此展示"等待家长确认"。
 */
@Singleton
class ExtensionRequestManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mqttManager: MqttManager,
    private val appTimeController: AppTimeController,
    private val json: Json,
) {

    enum class Status { PENDING, APPROVED, DENIED, EXPIRED, FAILED }

    @Serializable
    data class RequestState(
        val requestId: String,
        val packageName: String,
        val appLabel: String,
        val minutes: Int,
        val status: Status,
        val createdAt: Long,
        val decidedAt: Long? = null,
        /** 批准时实际给的分钟数(家长可改) */
        val grantedMinutes: Int? = null,
    )

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val nm by lazy { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private val _requests = MutableStateFlow<Map<String, RequestState>>(emptyMap())
    /** 包名 → 最近一次申请状态。 */
    val requests: StateFlow<Map<String, RequestState>> = _requests.asStateFlow()

    init {
        val stored = runCatching {
            json.decodeFromString(ListSerializer(RequestState.serializer()), prefs.getString(KEY_STATE, "[]") ?: "[]")
        }.getOrDefault(emptyList())
        _requests.value = stored.associateBy { it.packageName }
        expireStale()
        ensureChannel()
    }

    /** 某包当前状态(已处理过期)。 */
    fun stateFor(pkg: String): RequestState? {
        expireStale()
        return _requests.value[pkg]
    }

    /**
     * 发起申请。同一包已有未过期 PENDING 时直接返回该申请(防刷)。
     * @return 新的(或已有的)申请状态;未配对返回 null
     */
    fun request(pkg: String, minutes: Int, reason: String? = null): RequestState? {
        expireStale()
        _requests.value[pkg]?.let { if (it.status == Status.PENDING) return it }
        val deviceId = mqttManager.currentDeviceId() ?: return null
        val label = appTimeController.labelOf(pkg)
        val now = System.currentTimeMillis()
        val req = ExtensionRequest(
            requestId = UUID.randomUUID().toString(),
            packageName = pkg,
            appLabel = label,
            minutes = minutes.coerceIn(1, 240),
            reason = reason?.take(200)?.takeIf { it.isNotBlank() },
            ts = now,
        )
        val msg = WsMessage(
            id = req.requestId,
            type = MessageType.EXTENSION_REQUEST,
            payload = json.encodeToString(ExtensionRequest.serializer(), req),
            timestamp = now,
        )
        val sent = mqttManager.publish(msg, MqttTopics.request(deviceId), qos = 1)
        Log.i(TAG, "extension request ${req.requestId} pkg=$pkg min=${req.minutes} sent=$sent")
        val state = RequestState(
            requestId = req.requestId,
            packageName = pkg,
            appLabel = label,
            minutes = req.minutes,
            status = if (sent) Status.PENDING else Status.FAILED,
            createdAt = now,
        )
        update(state)
        return state
    }

    /** 处理 APP_TIME_EXTEND(批准)。 */
    fun onApproved(payload: AppTimeExtend) {
        val minutes = payload.minutes
        if (payload.packageName.isBlank() || minutes <= 0) {
            Log.w(TAG, "APP_TIME_EXTEND ignored: invalid payload $payload")
            return
        }
        appTimeController.extend(payload.packageName, minutes)
        val prev = _requests.value[payload.packageName]
        val label = prev?.appLabel ?: appTimeController.labelOf(payload.packageName)
        update(
            (prev?.takeIf { payload.requestId == null || it.requestId == payload.requestId }
                ?: RequestState(
                    requestId = payload.requestId ?: "remote-${System.currentTimeMillis()}",
                    packageName = payload.packageName,
                    appLabel = label,
                    minutes = minutes,
                    status = Status.APPROVED,
                    createdAt = System.currentTimeMillis(),
                )).copy(status = Status.APPROVED, decidedAt = System.currentTimeMillis(), grantedMinutes = minutes)
        )
        notifyUser(payload.packageName, "延时申请已通过", "「$label」今天可以再用 $minutes 分钟")
    }

    /** 处理 APP_TIME_EXTEND_DENIED(拒绝)。 */
    fun onDenied(payload: AppTimeExtendDenied) {
        val prev = _requests.value[payload.packageName]
        val label = prev?.appLabel ?: appTimeController.labelOf(payload.packageName)
        if (prev != null && (payload.requestId == null || prev.requestId == payload.requestId)) {
            update(prev.copy(status = Status.DENIED, decidedAt = System.currentTimeMillis()))
        }
        notifyUser(payload.packageName, "延时申请未通过", "「$label」的延时申请被拒绝了,休息一下吧")
    }

    private fun expireStale() {
        val now = System.currentTimeMillis()
        val cur = _requests.value
        val changed = cur.mapValues { (_, s) ->
            if (s.status == Status.PENDING && now - s.createdAt > EXPIRE_MS) s.copy(status = Status.EXPIRED) else s
        }
        if (changed != cur) {
            _requests.value = changed
            save()
        }
    }

    private fun update(state: RequestState) {
        _requests.value = _requests.value + (state.packageName to state)
        save()
    }

    private fun save() {
        val list = _requests.value.values.toList()
        prefs.edit().putString(KEY_STATE, json.encodeToString(ListSerializer(RequestState.serializer()), list)).apply()
    }

    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "延时申请结果", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "家长批准或拒绝「再用一会儿」申请时提醒"
            }
        )
    }

    private fun notifyUser(pkg: String, title: String, text: String) {
        runCatching {
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                NOTIFY_BASE + (pkg.hashCode() and 0xFFFF),
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    companion object {
        private const val TAG = "ExtensionRequest"
        private const val PREFS = "extension_requests"
        private const val KEY_STATE = "state"
        private const val CHANNEL = "extension_result"
        private const val NOTIFY_BASE = 5000
        /** 契约 §3:超过 30 分钟未决自动过期 */
        const val EXPIRE_MS = 30L * 60 * 1000
    }
}
