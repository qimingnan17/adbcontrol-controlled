package com.adbcontrol.controlled.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设备管理状态桥接。README 7:DeviceAdmin 激活后用于 lockNow / 防卸载 / 隐藏应用。
 *
 * 由 [ControlledDeviceAdminReceiver] 在 enabled/disabled 回调中更新状态。
 */
object DeviceAdminComponent {

    private val active = AtomicBoolean(false)

    fun setActive(value: Boolean) { active.set(value) }
    fun isActive(): Boolean = active.get()

    /** 检查并刷新状态(冷启时 DeviceAdminReceiver 不会回调,需主动查)。 */
    fun refresh(context: Context, adminComponent: ComponentName): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isAdmin = dpm.isAdminActive(adminComponent)
        active.set(isAdmin)
        return isAdmin
    }
}
