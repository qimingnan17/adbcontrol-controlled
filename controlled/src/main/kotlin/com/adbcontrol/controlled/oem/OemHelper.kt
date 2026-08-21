package com.adbcontrol.controlled.oem

import android.os.Build
import android.util.Log

/**
 * 厂商 ROM 识别工具。无状态、无 Context 依赖,可在任意线程调用。
 *
 * 不引入小米 / Huawei HMS / OPPO SDK,所有判断基于 [Build.MANUFACTURER] 与
 * SystemProperties 反射读取(失败一律返回安全默认值,不抛异常)。
 *
 * 用途:在 OEM 设置跳转、MIUI 通知渠道适配、Shizuku 引导等位置做 ROM 识别。
 */
object OemHelper {

    private const val TAG = "OemHelper"

    /** 支持专项适配的厂商集合,UNKNOWN 厂商走通用回退。 */
    enum class Oem { XIAOMI, HUAWEI, OPPO, VIVO, MEIZU, SAMSUNG, OTHER }

    /** 当前设备厂商(归一化为 [Oem]);Honor/Realme/OnePlus 归到对应主品牌。 */
    val oem: Oem by lazy {
        val m = Build.MANUFACTURER?.lowercase().orEmpty()
        when {
            m == "xiaomi" -> Oem.XIAOMI
            m == "huawei" || m == "honor" -> Oem.HUAWEI
            m == "oppo" || m == "realme" || m == "oneplus" -> Oem.OPPO
            m == "vivo" || m == "iqoo" -> Oem.VIVO
            m == "meizu" -> Oem.MEIZU
            m == "samsung" -> Oem.SAMSUNG
            else -> Oem.OTHER
        }
    }

    // ---------- MIUI ----------

    /** 是否为 MIUI(同时校验厂商 = xiaomi 与 ro.miui.ui.version.name 存在)。 */
    fun isMiui(): Boolean =
        oem == Oem.XIAOMI && !systemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    /**
     * MIUI 大版本判断。优先用数字 version code,缺失时退回 V13/V14 等 V 前缀名。
     * @param version 期望最低的 MIUI 大版本(如 11、12、13、14)
     * @return 当前 MIUI 版本 >= [version];非 MIUI 永远返回 false
     */
    fun isMiuiVersionGE(version: Int): Boolean {
        if (!isMiui()) return false
        val code = systemProperty("ro.miui.version_code")
            ?: systemProperty("ro.mi.version.code")
            ?: systemProperty("ro.miui.version.code")
        val codeInt = code?.toIntOrNull()
        if (codeInt != null) return codeInt >= version

        val name = systemProperty("ro.miui.ui.version.name").orEmpty()
        val nameInt = name.removePrefix("V").toIntOrNull()
        return nameInt != null && nameInt >= version
    }

    // ---------- 其他厂商 ----------

    fun isHuawei(): Boolean = oem == Oem.HUAWEI
    fun isOppo(): Boolean = oem == Oem.OPPO
    fun isVivo(): Boolean = oem == Oem.VIVO
    fun isMeizu(): Boolean = oem == Oem.MEIZU
    fun isSamsung(): Boolean = oem == Oem.SAMSUNG

    /**
     * 反射读取 android.os.SystemProperties.get(key),失败返回 null。
     * 仅作内部检测用,不依赖 hidden-api-stub(通过反射绕过编译期约束)。
     */
    fun systemProperty(name: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java)
        method.invoke(null, name) as? String
    }.onFailure { Log.v(TAG, "sysprop $name unreadable: ${it.message}") }.getOrNull()
}
