package com.adbcontrol.controlled.update

import com.adbcontrol.shared.model.UpdateCheckResponse
import java.io.File

/**
 * 软件更新通道抽象。README 第十一章。
 *
 * 双通道:
 * - [PlayAppUpdateChannel]:上架 Play Store 版本
 * - [SelfHostedUpdateChannel]:自分发 APK(差分包 + 全量 fallback + Shizuku 静默安装)
 */
interface UpdateChannel {

    /** 检查更新。无更新返回 null。 */
    suspend fun check(): UpdateCheckResponse?

    /** 下载更新包(优先差分,失败回退全量),返回本地文件。 */
    suspend fun download(info: UpdateCheckResponse, onProgress: (Int) -> Unit = {}): File

    /** 安装(Shizuku 可用时静默 pm install,否则弹安装确认)。 */
    suspend fun install(apk: File): InstallResult

    data class InstallResult(val success: Boolean, val message: String = "")
}
