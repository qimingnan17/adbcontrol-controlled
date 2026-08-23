package com.adbcontrol.controlled.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adbcontrol.controlled.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTA 更新编排器(自动静默更新策略)。
 *
 * 触发源(全部收敛到 [runOnce],Mutex 防重入):
 * - push 推送:后端发布新版本时广播 update_available(CommandHandler 调 [trigger])
 * - 周期巡检:[startPeriodicChecks] 启动后每 6h 检查一次(服务启动 30min 后先查一次)
 * - 手动:App 内"立即检查更新"按钮([trigger]("manual"))
 *
 * 流程:check → download(通知栏进度) → install(Shizuku 静默 / 系统确认回退)
 * → report 上报服务端。状态经 [state] 暴露给 App 内更新卡片。
 */
@Singleton
class UpdateRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channel: SelfHostedUpdateChannel,
) {

    data class UpdateUiState(
        val busy: Boolean = false,
        /** 当前阶段文案,如 "正在检查更新…" / "发现新版本 v1.0.1，下载中…" */
        val statusText: String = "",
        /** 下载进度 0-100;-1 表示无进度条 */
        val progress: Int = -1,
        /** 检测到的可用新版本名 */
        val availableVersion: String? = null,
        /** 最近一次失败原因 */
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val periodicStarted = AtomicBoolean(false)
    private val nm by lazy { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    init { ensureChannel() }

    /** 服务启动时调用:30min 后首查,之后每 6h 一次。重复调用安全。 */
    fun startPeriodicChecks() {
        if (!periodicStarted.compareAndSet(false, true)) return
        scope.launch {
            delay(INITIAL_CHECK_DELAY_MS)
            runOnce("startup")
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                runOnce("periodic")
            }
        }
    }

    /** 异步触发一次完整流程(push/手动入口)。周期巡检有最小间隔节流。 */
    fun trigger(source: String) {
        scope.launch { runOnce(source) }
    }

    suspend fun runOnce(source: String) {
        if (!mutex.tryLock()) return
        try {
            // 周期巡检节流:距上次检查不足 6h 跳过(设备频繁重启场景防风暴)
            if (source == "periodic") {
                val last = prefs.getLong(KEY_LAST_CHECK, 0L)
                if (System.currentTimeMillis() - last < PERIODIC_INTERVAL_MS) return
            }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

            _state.value = UpdateUiState(busy = true, statusText = "正在检查更新…")

            val info = channel.check() ?: run {
                _state.value = UpdateUiState(statusText = "已是最新版本")
                notifyDone("已是最新版本")
                return
            }
            Log.i(TAG, "update available: v${info.latestVersionName}(${info.latestVersionCode}) from=$source")

            _state.value = UpdateUiState(
                busy = true,
                statusText = "发现新版本 ${info.latestVersionName}，下载中…",
                progress = 0,
                availableVersion = info.latestVersionName,
            )
            showProgress("下载更新 ${info.latestVersionName}", 0)

            val started = System.currentTimeMillis()
            val apk = channel.download(info) { p ->
                _state.value = _state.value.copy(progress = p)
                showProgress("下载更新 ${info.latestVersionName}", p)
            }

            _state.value = _state.value.copy(
                statusText = "下载完成，静默安装中…",
                progress = -1,
            )
            showIndeterminate("正在安装 ${info.latestVersionName}")

            val result = channel.install(apk)
            channel.report(info.latestVersionCode, result.success, result.message, System.currentTimeMillis() - started)

            val text = if (result.success) "已安装新版本 ${info.latestVersionName}"
            else "安装失败：${result.message.take(80)}"
            _state.value = UpdateUiState(
                busy = false,
                statusText = text,
                availableVersion = info.latestVersionName,
                lastError = if (result.success) null else result.message,
            )
            notifyDone(text)
            Log.i(TAG, "install done success=${result.success} msg=${result.message}")
        } catch (t: Throwable) {
            Log.e(TAG, "update flow failed", t)
            _state.value = UpdateUiState(
                busy = false,
                statusText = "更新失败：${t.message?.take(80)}",
                lastError = t.message,
            )
            notifyDone("更新失败：${t.message?.take(80)}")
        } finally {
            cancelProgress()
        }
    }

    // ---------- 通知 ----------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_OTA) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_OTA, "应用更新", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "OTA 更新进度与结果"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun builder(title: String, text: String) =
        NotificationCompat.Builder(context, CHANNEL_OTA)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)

    private fun showProgress(title: String, progress: Int) {
        runCatching {
            nm.notify(NOTIFY_ID, builder(title, "$progress%").setOngoing(true)
                .setProgress(100, progress, false).build())
        }
    }

    private fun showIndeterminate(text: String) {
        runCatching {
            nm.notify(NOTIFY_ID, builder("应用更新", text).setOngoing(true)
                .setProgress(0, 0, true).build())
        }
    }

    private fun notifyDone(text: String) {
        runCatching {
            nm.notify(NOTIFY_ID, builder("应用更新", text).setOngoing(false).setAutoCancel(true).build())
        }
    }

    private fun cancelProgress() {
        runCatching { nm.cancel(NOTIFY_ID) }
    }

    companion object {
        private const val TAG = "UpdateRunner"
        private const val PREFS_NAME = "ota_state"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val CHANNEL_OTA = "app_update"
        private const val NOTIFY_ID = 3001

        /** 服务启动后首次巡检延迟。 */
        private const val INITIAL_CHECK_DELAY_MS = 30L * 60 * 1000

        /** 巡检间隔与节流阈值:6 小时。 */
        const val PERIODIC_INTERVAL_MS: Long = 6L * 60 * 60 * 1000
    }
}
