package com.adbcontrol.controlled.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 玻璃拟态深色主题色板。README 15.2 / 15.3。
 *
 * 设计取向:深午夜蓝底 + 青/品红双色强调,跳出"紫色渐变 + 白底"AI 模板。
 */
object AppColors {
    // 背景层
    val bgBase = Color(0xFF060812)
    val bgDeep = Color(0xFF02030A)

    // 玻璃面
    val glassBg = Color.White.copy(alpha = 0.045f)
    val glassBgStrong = Color.White.copy(alpha = 0.075f)
    val glassBorder = Color.White.copy(alpha = 0.08f)
    val glassBorderStrong = Color.White.copy(alpha = 0.14f)
    val glassHl = Color.White.copy(alpha = 0.22f)

    // 强调色
    val cyan = Color(0xFF4FD1E0)
    val magenta = Color(0xFFFF5FAD)
    val emerald = Color(0xFF4ADE80)
    val amber = Color(0xFFFBBF24)
    val rose = Color(0xFFFF6B6B)
    val slate = Color(0xFF64748B)

    // 文字
    val textPrimary = Color(0xFFE8ECF4)
    val textSecondary = Color(0xFFC7CEDD)
    val textTertiary = Color(0xFF9AA3B8)
    val textDisabled = Color(0xFF64748B)
}

object AppFonts {
    // 字体回退:仓库未内置字体文件时回落到系统默认,避免硬依赖。
    val display = FontFamilyFallback(listOf("Bricolage Grotesque", "Manrope"))
    val body = FontFamilyFallback(listOf("Manrope"))
    val mono = FontFamilyFallback(listOf("JetBrains Mono", "monospace"))
}

data class FontFamilyFallback(val names: List<String>)

object AppRadii {
    val sm = 10
    val md = 16
    val lg = 24
    val xl = 32
    val pill = 999
}
