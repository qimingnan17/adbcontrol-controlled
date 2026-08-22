package com.adbcontrol.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 应用禁用时间窗纯逻辑测试。覆盖受控端 AppTimeController 委托的路径,
 * 特别是跨零点(22:00-07:00)和边界时刻(恰好到点/离点)的判定。
 */
class AppTimeWindowsTest {

    @Test
    fun `normal window contains interior and excludes exterior`() {
        // 09:00-12:00
        val start = AppTimeWindows.parseHm("09:00")
        val end = AppTimeWindows.parseHm("12:00")
        assertTrue(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("09:00"), start, end))
        assertTrue(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("10:30"), start, end))
        assertFalse(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("12:00"), start, end))
        assertFalse(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("08:59"), start, end))
        assertFalse(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("12:01"), start, end))
    }

    @Test
    fun `overnight window spans midnight`() {
        // 22:00-07:00
        val start = AppTimeWindows.parseHm("22:00")
        val end = AppTimeWindows.parseHm("07:00")
        assertTrue(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("22:00"), start, end))
        assertTrue(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("23:59"), start, end))
        assertTrue(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("00:00"), start, end))
        assertTrue(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("06:59"), start, end))
        assertFalse(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("07:00"), start, end))
        assertFalse(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("12:00"), start, end))
        assertFalse(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("21:59"), start, end))
    }

    @Test
    fun `parseHm rejects malformed input`() {
        assertEquals(-1, AppTimeWindows.parseHm("2200"))
        assertEquals(-1, AppTimeWindows.parseHm("22:00:00"))
        assertEquals(-1, AppTimeWindows.parseHm("24:00"))
        assertEquals(-1, AppTimeWindows.parseHm("22:60"))
        assertEquals(-1, AppTimeWindows.parseHm("aa:bb"))
        assertEquals(-1, AppTimeWindows.parseHm(""))
    }

    @Test
    fun `parseHm accepts valid input`() {
        assertEquals(0, AppTimeWindows.parseHm("00:00"))
        assertEquals(23 * 60 + 59, AppTimeWindows.parseHm("23:59"))
        assertEquals(22 * 60, AppTimeWindows.parseHm("22:00"))
    }

    @Test
    fun `zero length window means always in window (start==end)`() {
        // start==end 被 isInWindow 当作“全天”——受控端用这种窗口实现全天禁用
        assertTrue(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("12:00"), 600, 600))
        assertTrue(AppTimeWindows.isInWindow(AppTimeWindows.parseHm("00:00"), 600, 600))
    }
}
