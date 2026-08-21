package com.adbcontrol.controlled.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adbcontrol.controlled.oem.OemAccessibilityGuard
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager 周期兜底。README 3.1 L7。
 *
 * - 每 15 分钟检查 ControlledService 是否运行,不运行则重启
 * - 检查无障碍服务是否仍连着(MIUI 11+ 7 天自动关 / 用户手动关 / 系统重启未自启),掉线日志告警
 * - 检查 MQTT 连接状态,断开则触发重连(Paho 自动重连通常已处理)
 * - 周期下限 15 分钟(WorkManager 限制)
 */
@HiltWorker
class HeartbeatGuardWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            if (!isServiceRunning()) {
                Log.i(TAG, "ControlledService not running, restarting")
                ControlledService.start(applicationContext)
            } else {
                Log.d(TAG, "ControlledService already running")
            }
            // 无障碍健康周期复查(MIUI 11+ 自动关检测);普通应用无法静默重开,仅日志告警,
            // 真正未连的状态由健康上报通道反馈给主控端 UI。
            OemAccessibilityGuard.checkAndLog(applicationContext)
            Result.success()
        }.getOrElse {
            // 不吞 CancellationException,否则 WorkManager 取消协程时失效
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.e(TAG, "heartbeat guard failed", it)
            Result.retry()
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        return runCatching {
            @Suppress("DEPRECATION")
            manager.getRunningServices(Int.MAX_VALUE).orEmpty().any {
                it.service.className == ControlledService::class.java.name
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "HeartbeatGuardWorker"
        private const val UNIQUE_WORK_NAME = "controlled_heartbeat_guard"

        /** 注册 15 分钟周期兜底任务。 */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HeartbeatGuardWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "heartbeat guard enqueued")
        }
    }
}
