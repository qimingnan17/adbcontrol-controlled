package com.adbcontrol.controlled

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.executor.ShizukuExecutor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 被控端 Application 入口。
 *
 * 职责:
 * - Hilt 依赖图根
 * - WorkManager 初始化(注入 HiltWorkerFactory,用于 [com.adbcontrol.controlled.service.HeartbeatGuardWorker])
 * - Shizuku Binder 绑定(主桥接,见 README 3.2)
 * - 全局 ApplicationContext 暴露
 */
@HiltAndroidApp
class ControlledApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var shizukuExecutor: ShizukuExecutor
    @Inject lateinit var configStore: ConfigStore

    override fun onCreate() {
        super.onCreate()
        instance = this

        // WorkManager 由 Configuration.Provider 懒加载:首次 WorkManager.getInstance(ctx)
        // 触发,无需手动 initialize(手动 initialize 会导致 IllegalStateException: already initialized)。
        // Manifest 已移除 default initializer,Configuration.Provider 注入 HiltWorkerFactory。

        // Shizuku 主桥接:绑定 sender service,接收 binder 死亡/恢复回调
        shizukuExecutor.bind()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        @Volatile
        private var instance: ControlledApp? = null

        fun get(context: Context): ControlledApp =
            (context.applicationContext as? ControlledApp)
                ?: instance
                ?: error("ControlledApp not initialized")

        fun appVersionName(context: Context): String =
            runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName
            }.getOrDefault("unknown")

        fun appVersionCode(context: Context): Long =
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching {
                    context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .longVersionCode
                }.getOrDefault(0L)
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .versionCode.toLong()
                }.getOrDefault(0L)
            }
    }
}
