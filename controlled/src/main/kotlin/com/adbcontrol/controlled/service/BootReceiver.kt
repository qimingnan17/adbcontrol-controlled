package com.adbcontrol.controlled.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

/**
 * 开机自启 Receiver。README 3.1 L5。
 *
 * - BOOT_COMPLETED:正常开机
 * - LOCKED_BOOT_COMPLETED:锁屏也能直启(directBootAware)
 * - MY_PACKAGE_REPLACED:应用自更新后重启服务
 *
 * 仅当已配对(configStore 有配置)时启动 Agent,避免未配对设备空跑。
 *
 * MIUI/HyperOS 必防 CRITICAL:LOCKED_BOOT_COMPLETED 阶段仍在 Direct Boot(CE 存储锁定),
 * ControlledService 内部会调用 EncryptedFile.openFileInput() 读取凭证,在 CE 不可用时
 * 直接抛异常导致开机即崩。因此在 ACTION_LOCKED_BOOT_COMPLETED 分支中必须先检查
 * UserManager.isUserUnlocked(),CE 未解锁时 return 等待后续 ACTION_BOOT_COMPLETED 再启。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // MIUI/HyperOS 默认都有锁屏 PIN,首次开机到 LOCKED_BOOT_COMPLETED 时
                // CE 未解锁,EncryptedFile 加载会崩。此时仅记日志,等 BOOT_COMPLETED 再启动。
                val unlocked = runCatching {
                    context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
                }.getOrDefault(false)
                Log.i(TAG, "LOCKED_BOOT_COMPLETED, userUnlocked=$unlocked → ${if (unlocked) "start" else "skip (wait BOOT_COMPLETED)"}")
                if (unlocked) ControlledService.start(context)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "boot received: ${intent.action}")
                ControlledService.start(context)
            }
        }
    }

    companion object { private const val TAG = "BootReceiver" }
}
