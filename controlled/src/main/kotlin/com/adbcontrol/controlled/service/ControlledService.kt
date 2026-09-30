package com.adbcontrol.controlled.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.adbcontrol.controlled.R
import com.adbcontrol.controlled.apptime.AppTimeController
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.net.CommandHandler
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.controlled.oem.MiuiAdapter
import com.adbcontrol.controlled.oem.OemAccessibilityGuard
import com.adbcontrol.controlled.telemetry.TelemetryEngine
import com.adbcontrol.controlled.ui.MainActivity
import com.adbcontrol.controlled.update.UpdateRunner
import com.adbcontrol.shared.model.AppConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 常驻前台 Service。README 3.1 11 层保活核心。
 *
 * 实现的保活层:
 * - L1 前台通知(startForeground + setOngoing)
 * - L2 通知渠道 IMPORTANCE_LOW,channel "service_foreground"
 * - L3 foregroundServiceType="connectedDevice|dataSync|location"(Manifest)
 * - L6 onTaskRemoved 重启
 * - L7 WorkManager 周期兜底(HeartbeatGuardWorker,见 [ControlledApp])
 * - L9 MQTT 自动重连(Paho isAutomaticReconnect)
 * - L11 LWT 兜底(MqttManager 配置 device/offline/{deviceId})
 *
 * 电池白名单(L4)/开机自启(L5)/厂商后台(L8)/主控心跳(L10)分由 UI 引导与 BootReceiver 实现。
 */
@AndroidEntryPoint
class ControlledService : LifecycleService() {

    @Inject lateinit var configStore: ConfigStore
    @Inject lateinit var mqttManager: MqttManager
    @Inject lateinit var telemetryEngine: TelemetryEngine
    @Inject lateinit var commandHandler: CommandHandler
    @Inject lateinit var dispatcher: CommandDispatcher
    @Inject lateinit var miuiAdapter: MiuiAdapter
    @Inject lateinit var appTimeController: AppTimeController
    @Inject lateinit var updateRunner: UpdateRunner

    /** agent 是否已用有效配置启动过;配对完成后由 onStartCommand 重载触发。 */
    @Volatile private var agentStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        ensureNotificationChannel()
        startForegroundCompat()

        // L7:周期兜底心跳(15 分钟)
        HeartbeatGuardWorker.enqueue(this)

        // MIUI 11+ 会在用户长时间无操作后自动关闭无障碍,启动时检查并日志告警;
        // 后续由 HeartbeatGuardWorker 周期复查。
        OemAccessibilityGuard.checkAndLog(this)

        // 启动 MQTT + 遥测(若有配置)
        startAgent()
    }

    private fun startAgent() {
        val config = configStore.load() ?: run {
            Log.w(TAG, "no config, agent idle until paired")
            return
        }
        agentStarted = true
        lifecycleScope.launch {
            mqttManager.listener = commandHandler
            mqttManager.start(config)
            telemetryEngine.start(config)
            appTimeController.start()
        }
        // OTA 周期巡检(内部 30min 首查 + 6h 间隔,幂等)
        updateRunner.startPeriodicChecks()
    }

    /** 配对/续期后由 UI 调用重启 agent。 */
    fun restartWithConfig(config: AppConfig) {
        // C13:lifecycleScope.launch 默认派发 Dispatchers.Main.immediate,而
        // mqttManager.stop()(disconnectForcibly 同步阻塞数秒)与 configStore.save()(磁盘 IO)
        // 在主线程上会长时间阻塞 → ANR。整体切到 IO 池执行。
        lifecycleScope.launch(Dispatchers.IO) {
            mqttManager.stop()
            telemetryEngine.stop()
            configStore.save(config)
            agentStarted = true
            mqttManager.listener = commandHandler
            mqttManager.start(config)
            telemetryEngine.start(config)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_RESTART_AGENT) {
            val cfg = configStore.load()
            if (cfg != null) {
                Log.i(TAG, "onStartCommand: reload config and restart agent")
                restartWithConfig(cfg)
            }
        } else if (!agentStarted) {
            Log.i(TAG, "onStartCommand: agent not started yet, (re)loading config")
            startAgent()
        }
        // START_STICKY:系统尽量重建服务(配合 L6 onTaskRemoved)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // L6:任务从最近任务滑掉时重启服务
        // C12:Android 12+ 后台态直接 startForegroundService 会被系统拒
        // (ForegroundServiceStartNotAllowedException),且原 runCatching 会静默吞掉,
        // 导致服务无法自启。改用 WorkManager 排一个 OneTimeWorkRequest<HeartbeatGuardWorker>,
        // 由 Worker 检查服务是否存活并按需拉起(经 WorkManager 调度,规避后台启动限制)。
        Log.i(TAG, "onTaskRemoved, scheduling restart via WorkManager")
        val request = OneTimeWorkRequest.Builder(HeartbeatGuardWorker::class.java).build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            RESTART_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // 纯启动式服务,不绑定
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        runCatching { appTimeController.stop() }
        runCatching { telemetryEngine.stop() }
        runCatching { mqttManager.stop() }
        super.onDestroy()
    }

    // ---------- 前台通知 ----------

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_FOREGROUND) == null) {
                val channel = NotificationChannel(
                    CHANNEL_FOREGROUND,
                    getString(R.string.notif_channel_foreground),
                    NotificationManager.IMPORTANCE_LOW, // L2:无声音不打扰
                ).apply {
                    description = getString(R.string.notif_channel_foreground_desc)
                    setShowBadge(false)
                }
                // MIUI 11+ 专项:IMPORTANCE_LOW + setShowBadge(false) + setBypassDnd(true),
                // 且不 setSound(Uri)(部分 MIUI 版本会强制归到"打扰")。非 MIUI 仅 setShowBadge(false)。
                miuiAdapter.applyForegroundChannel(channel)
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setContentTitle(getString(R.string.notif_foreground_title))
            .setContentText(getString(R.string.notif_foreground_text))
            .setContentIntent(contentIntent)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true) // L1:不可清除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 13-:无 type 强制,两参即"不带细分类型"
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        // Android 14+:location 类型要求 ACCESS_FINE/COARSE 已授予且处于可定位资格状态,
        // connectedDevice 亦有个别资格条件;未满足时携带对应 type 会抛 SecurityException
        // (后台由 BootReceiver / HeartbeatGuardWorker 拉起时最易触发)。
        // 动态裁剪:定位资格按运行时授权判断,授权过才带 location。
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasLocation) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        // 注意:两参 startForeground 在 API 34+ 语义是 FOREGROUND_SERVICE_TYPE_MANIFEST
        // (请求清单声明的全部类型),不能当"不带类型"的兜底,否则 location 再次抛异常
        // 且未被捕获 → FGS 崩溃循环(实测复现)。必须逐级显式降级到最终 0 类型。
        val candidates = buildList {
            add(types)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        for (candidate in candidates) {
            try {
                startForeground(NOTIFICATION_ID, notification, candidate)
                if (candidate != types) {
                    Log.w(TAG, "startForeground degraded to type mask $candidate")
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with type mask $candidate rejected", e)
            }
        }
        try {
            startForeground(NOTIFICATION_ID, notification, 0)
            Log.w(TAG, "startForeground succeeded with untyped FGS; 定位/设备类遥测暂不可用")
        } catch (e: Exception) {
            // 理论上不可达:0 类型不含任何需审批的 type。真发生时只能交由系统
            // 按"未及时 startForeground"处理,这里保证不留未捕获异常。
            Log.e(TAG, "startForeground fully rejected; service will be stopped by system", e)
        }
    }

    companion object {
        private const val TAG = "ControlledService"
        private const val CHANNEL_FOREGROUND = "service_foreground"
        private const val NOTIFICATION_ID = 1001
        private const val RESTART_WORK_NAME = "controlled_restart_on_task_removed"
        const val ACTION_RESTART_AGENT = "com.adbcontrol.controlled.ACTION_RESTART_AGENT"

        /** 启动服务(供 BootReceiver / UI 调用)。 */
        fun start(context: Context) {
            val intent = Intent(context, ControlledService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "start service failed", it) }
        }

        /** 重载配置并重启 MQTT / 遥测。 */
        fun restartAgent(context: Context) {
            val intent = Intent(context, ControlledService::class.java).apply {
                action = ACTION_RESTART_AGENT
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "restart agent failed", it) }
        }

        /** 停止服务。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, ControlledService::class.java))
        }
    }
}
