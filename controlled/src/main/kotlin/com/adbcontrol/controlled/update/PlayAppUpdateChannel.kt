package com.adbcontrol.controlled.update

import android.app.Activity
import android.content.Context
import com.adbcontrol.shared.model.UpdateCheckResponse
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import java.io.File

/**
 * Play App Update 通道。README 11.1。
 *
 * - 上架 Play Store 版本使用
 * - flexible(后台下载)/ immediate(全屏强制)两种模式
 * - 优先级 priority=CRITICAL 时用 immediate
 */
class PlayAppUpdateChannel(
    context: Context,
    private val activityProvider: () -> Activity?,
) : UpdateChannel {

    private val appUpdateManager = AppUpdateManagerFactory.create(context.applicationContext)

    override suspend fun check(): UpdateCheckResponse? {
        val info = appUpdateManager.appUpdateInfo.awaitResult() ?: return null
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return null
        val isImmediate = info.updatePriority() >= 5 || info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        return UpdateCheckResponse(
            hasUpdate = true,
            latestVersionCode = info.availableVersionCode(),
            latestVersionName = "",
            releaseNotes = "",
            priority = if (isImmediate) UpdateCheckResponse.UpdatePriority.CRITICAL else UpdateCheckResponse.UpdatePriority.NORMAL,
            forceUpdate = isImmediate,
        )
    }

    override suspend fun download(info: UpdateCheckResponse, onProgress: (Int) -> Unit): File {
        // Play 自管理下载,触发 update flow
        val activity = activityProvider() ?: error("no activity to start Play update flow")
        val appUpdateInfo = appUpdateManager.appUpdateInfo.awaitResult()
            ?: error("no app update info")
        val type = if (info.forceUpdate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        appUpdateManager.startUpdateFlowForResult(
            appUpdateInfo, type, activity, REQUEST_CODE,
        )
        // Play 通道无本地 APK 文件返回
        return File(context.cacheDir, "play_update_placeholder")
    }

    override suspend fun install(apk: File): UpdateChannel.InstallResult {
        // Play 通道由系统安装,无需显式 install
        return UpdateChannel.InstallResult(success = true, message = "installed via Play")
    }

    private val context = context.applicationContext

    private fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T? {
        // 简化同步等待;实际应挂起协程。TODO: 用 suspendCancellableCoroutine + addOnCompleteListener
        val latch = java.util.concurrent.CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        var result: T? = null
        addOnSuccessListener { result = it; latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    companion object { private const val REQUEST_CODE = 7701 }
}
