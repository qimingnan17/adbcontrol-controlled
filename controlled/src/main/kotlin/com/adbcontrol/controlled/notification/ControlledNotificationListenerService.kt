package com.adbcontrol.controlled.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.adbcontrol.controlled.accessibility.AccessibilityServiceBridge
import com.adbcontrol.shared.model.ActivityReport.ActivityEvent
import com.adbcontrol.controlled.telemetry.TelemetryEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 通知监听服务。README 第七章。
 *
 * - 监听通知发布/移除
 * - 作为 ADB 控制冗余(NotificationListener 提供独立的通知通道,不依赖 Shizuku)
 * - 可上报通知日志(notification_log)
 * - 可拦截/取消指定包名通知
 */
@AndroidEntryPoint
class ControlledNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var telemetryEngine: TelemetryEngine

    /** 拦截名单(主控下发,内存缓存)。 */
    private val blockedPackages = mutableSetOf<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName
        val notification = sbn.notification ?: return
        runCatching {
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            Log.d(TAG, "notif posted pkg=$pkg title=$title")

            // 拦截黑名单通知
            if (pkg in blockedPackages) {
                cancelNotification(sbn.key)
                Log.i(TAG, "blocked notification from $pkg")
                return@runCatching
            }

            // 通知作为 ActivityReporter 的 NOTIFICATION_POSTED 事件冗余上报
            telemetryEngine.reportActivity(pkg, ActivityEvent.NOTIFICATION_POSTED)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        telemetryEngine.reportActivity(sbn.packageName, ActivityEvent.NOTIFICATION_REMOVED)
    }

    /** 主控下发更新拦截名单。 */
    fun setBlockedPackages(packages: Set<String>) {
        synchronized(blockedPackages) {
            blockedPackages.clear()
            blockedPackages.addAll(packages)
        }
    }

    companion object { private const val TAG = "NotifListener" }
}
