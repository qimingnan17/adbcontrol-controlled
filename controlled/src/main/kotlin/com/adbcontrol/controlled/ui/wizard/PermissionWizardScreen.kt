package com.adbcontrol.controlled.ui.wizard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adbcontrol.controlled.ui.theme.StatusColors
import com.adbcontrol.controlled.ui.wizard.PermissionChecker.Id
import com.adbcontrol.controlled.ui.wizard.PermissionChecker.Status

/**
 * v2.0 权限自检向导。首次启动自动展示,之后可从首页"权限自检"重新打开。
 *
 * - 每次回到前台(从系统设置返回)自动重新检测
 * - 缺失项红色高亮 + "一键修复"
 * - MIUI/HyperOS 无法自动检测的项显示"我已设置"让用户手动确认
 */
@Composable
fun PermissionWizardScreen(
    onEnableAccessibilityViaShizuku: () -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val items = remember(tick) { PermissionChecker.check(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        if (!PermissionChecker.isNotificationOn(ctx)) PermissionChecker.start(ctx, PermissionChecker.notificationSettingsIntent(ctx))
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bgLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        if (!PermissionChecker.hasBackgroundLocation(ctx)) PermissionChecker.start(ctx, PermissionChecker.appDetailsIntent(ctx))
    }

    fun fix(id: Id) {
        when (id) {
            Id.SHIZUKU -> PermissionChecker.fixShizuku(ctx)
            Id.ACCESSIBILITY -> {
                if (PermissionChecker.shizukuStatus(ctx) == Status.GRANTED) onEnableAccessibilityViaShizuku()
                else if (!PermissionChecker.start(ctx, PermissionChecker.accessibilityIntent(ctx))) {
                    PermissionChecker.start(ctx, android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            Id.DEVICE_ADMIN -> PermissionChecker.start(ctx, PermissionChecker.deviceAdminIntent(ctx))
            Id.NOTIFICATION -> if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else PermissionChecker.start(ctx, PermissionChecker.notificationSettingsIntent(ctx))
            Id.LOCATION -> locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            Id.LOCATION_BACKGROUND -> if (!PermissionChecker.hasFineLocation(ctx)) {
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else {
                // Android 11+:系统会跳到设置页让用户选"始终允许"
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            Id.USAGE_ACCESS -> if (!PermissionChecker.start(ctx, PermissionChecker.usageAccessIntent(ctx))) {
                PermissionChecker.start(ctx, android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            Id.BATTERY_OPTIMIZATION -> if (!PermissionChecker.start(ctx, PermissionChecker.batteryIntent(ctx))) {
                PermissionChecker.start(ctx, android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            Id.MIUI_AUTOSTART -> PermissionChecker.fixMiuiAutostart(ctx)
            Id.MIUI_BG_POPUP -> PermissionChecker.fixMiuiBgPopup(ctx)
        }
    }

    val okCount = items.count { it.ok }
    val allRequired = PermissionChecker.allRequiredOk(items)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("权限自检", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "以下授权保证管控功能正常工作。红色为缺失项,点「一键修复」前往对应设置页,返回后自动重新检测。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { if (items.isEmpty()) 0f else okCount.toFloat() / items.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("$okCount / ${items.size} 项已就绪", style = MaterialTheme.typography.labelMedium)
            }
        }
        items(items, key = { it.id.name }) { item ->
            PermissionRow(
                item = item,
                onFix = { fix(item.id) },
                onConfirm = {
                    PermissionChecker.confirmManually(ctx, item.id)
                    tick++
                },
            )
        }
        item {
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = {
                    PermissionChecker.markWizardDone(ctx)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (allRequired) "完成" else "稍后再说(必需项未完成)") }
        }
    }
}

@Composable
private fun PermissionRow(item: PermissionChecker.Item, onFix: () -> Unit, onConfirm: () -> Unit) {
    val missing = item.status == Status.MISSING
    val error = MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (missing) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = if (missing) BorderStroke(1.5.dp, error) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (item.status) {
                    Status.GRANTED -> Icons.Filled.CheckCircle
                    Status.MISSING -> Icons.Filled.Error
                    Status.UNKNOWN -> Icons.Filled.Help
                },
                contentDescription = null,
                tint = when (item.status) {
                    Status.GRANTED -> StatusColors.ok
                    Status.MISSING -> error
                    Status.UNKNOWN -> StatusColors.warn
                },
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (missing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (item.required) "必需" else "可选",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (missing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.status == Status.UNKNOWN) {
                    Text("系统不允许自动检测,请在打开的页面中确认已开启", style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn)
                }
            }
            if (!item.ok) {
                Column(horizontalAlignment = Alignment.End) {
                    FilledTonalButton(onClick = onFix) { Text("一键修复") }
                    if (item.status == Status.UNKNOWN) {
                        TextButton(onClick = onConfirm) { Text("我已设置") }
                    }
                }
            }
        }
    }
}
