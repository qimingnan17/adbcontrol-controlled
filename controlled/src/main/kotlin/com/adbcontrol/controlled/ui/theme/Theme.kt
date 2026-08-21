package com.adbcontrol.controlled.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ControlledColorScheme = darkColorScheme(
    primary = AppColors.cyan,
    onPrimary = AppColors.bgDeep,
    secondary = AppColors.magenta,
    onSecondary = AppColors.bgDeep,
    tertiary = AppColors.emerald,
    background = AppColors.bgBase,
    onBackground = AppColors.textPrimary,
    surface = AppColors.glassBg,
    onSurface = AppColors.textPrimary,
    surfaceVariant = AppColors.glassBgStrong,
    onSurfaceVariant = AppColors.textSecondary,
    error = AppColors.rose,
    onError = Color.Black,
)

@Composable
fun ControlledTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 被控端固定深色玻璃主题
    MaterialTheme(
        colorScheme = ControlledColorScheme,
        content = content,
    )
}
