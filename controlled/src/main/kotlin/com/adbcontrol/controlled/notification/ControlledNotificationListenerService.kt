package com.adbcontrol.controlled.notification

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
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
 *
 * 内容不可用降级:部分 ROM 只把通知的"来源包名"开放给监听服务而脱敏掉标题/正文。
 * 连续 [CONTENT_UNAVAILABLE_THRESHOLD] 条通知拿不到任何内容时,判定该监听能力没有意义,
 * 通过 PackageManager 禁用本组件(setComponentEnabledSetting)——系统随即解绑监听服务,
 * 功能真正关闭(不再是"只记日志却拿不到内容")。需要恢复时到
 * 设置→特殊应用权限→通知使用权 重新开启即可。
 */
@AndroidEntryPoint
class ControlledNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var telemetryEngine: TelemetryEngine

    /** 拦截名单(主控下发,内存缓存)。 */
    private val blockedPackages = mutableSetOf<String>()

    /** 连续"只有包名、无标题无正文"的通知计数;达到阈值即禁用组件。 */
    private var contentUnavailableCount = 0

    /** 是否已判定内容不可用并停用(避免重复 disable)。 */
    private var disabled = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName
        val notification = sbn.notification ?: return

        // 已判定内容不可用 → 不再处理(组件随后被禁用)
        if (disabled) return

        runCatching {
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            Log.d(TAG, "notif posted pkg=$pkg title=$title")

            // 内容不可用:标题正文都为空。注意过滤掉本服务自己的前台常驻通知。
            if (pkg == packageName) return@runCatching
            if (title.isBlank() && text.isBlank()) {
                contentUnavailableCount++
                if (contentUnavailableCount >= CONTENT_UNAVAILABLE_THRESHOLD) {
                    disableFeature()
                }
                return@runCatching
            }
            contentUnavailableCount = 0

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

    /** 只拿到包名拿不到内容 → 停用本监听组件,功能彻底关闭。 */
    private fun disableFeature() {
        if (disabled) return
        disabled = true
        Log.w(TAG, "notification content unavailable for $CONTENT_UNAVAILABLE_THRESHOLD+ notifs, disabling feature")
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, ControlledNotificationListenerService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.e(TAG, "setComponentEnabledSetting failed", it) }
    }

    companion object {
        private const val TAG = "NotifListener"

        /** 连续只有包名、拿不到标题/正文达到此阈值即判定内容不可用并停用组件。 */
        private const val CONTENT_UNAVAILABLE_THRESHOLD = 5
    }
}
