package com.adbcontrol.controlled.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * v2.0:Material 3 + 动态取色(Android 12+ 跟随系统壁纸;MIUI/HyperOS 不支持 Monet 时回退品牌色)。
 * 跟随系统深浅色。旧版固定深色玻璃色板保留在 [AppColors] 供少量强调色使用。
 */
private val BrandDark = darkColorScheme(
    primary = AppColors.cyan,
    onPrimary = AppColors.bgDeep,
    secondary = AppColors.magenta,
    onSecondary = AppColors.bgDeep,
    tertiary = AppColors.emerald,
    error = AppColors.rose,
    onError = Color.Black,
)

private val BrandLight = lightColorScheme(
    primary = Color(0xFF00687A),
    secondary = Color(0xFFB0306F),
    tertiary = Color(0xFF1E6B3A),
    error = Color(0xFFBA1A1A),
)

@Composable
fun ControlledTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 状态语义色(与动态色搭配的固定语义:正常 / 警告 / 缺失)。 */
object StatusColors {
    val ok = Color(0xFF2E9E5B)
    val warn = Color(0xFFE0A100)
}
