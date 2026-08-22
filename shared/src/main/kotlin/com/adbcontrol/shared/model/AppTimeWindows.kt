package com.adbcontrol.shared.model

import java.util.Calendar

/**
 * 应用禁用时间窗判定(纯逻辑,无平台依赖,供受控端 AppTimeController 委托)。
 *
 * 支持跨零点窗口:22:00-07:00 视为"晚 10 点到次日早 7 点"。
 */
object AppTimeWindows {

    /** "HH:mm" → 当日分钟数;非法(缺冒号/越界/非数字)返回 -1。 */
    fun parseHm(hm: String): Int {
        val parts = hm.trim().split(":")
        if (parts.size != 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    /**
     * 当前是否处于窗口内。pre: start/end 均已过 [parseHm] 校验。
     * start == end 约定为"全天禁用"(避免无意选中同一时刻导致永不禁用)。
     */
    fun isInWindow(nowMin: Int, startMin: Int, endMin: Int): Boolean = when {
        startMin == endMin -> true
        startMin < endMin -> nowMin in startMin until endMin
        else -> nowMin >= startMin || nowMin < endMin
    }

    /** 当前时刻对应的"当日分钟数"(设备本地时区)。 */
    fun nowMinutesOfDay(cal: Calendar = Calendar.getInstance()): Int {
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }
}
