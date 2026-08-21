package com.adbcontrol.controlled.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理 Receiver。README 第七章。
 *
 * - lockNow(由 [com.adbcontrol.controlled.executor.DeviceAdminExecutor] 调)
 * - 防卸载(激活后无法卸载本应用)
 * - onDisableRequested 返回非空文案阻止用户取消授权
 */
class ControlledDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        DeviceAdminComponent.setActive(true)
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        DeviceAdminComponent.setActive(false)
        Log.w(TAG, "device admin disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // 阻止用户随意取消设备管理
        return "取消设备管理将导致远程锁屏、防卸载等能力失效,确定继续?"
    }

    companion object { private const val TAG = "DeviceAdminReceiver" }
}
