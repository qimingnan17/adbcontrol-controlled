package com.adbcontrol.controlled.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbcontrol.controlled.R
import com.adbcontrol.controlled.oem.OemBatterySettings
import com.adbcontrol.controlled.oem.OemHelper
import com.adbcontrol.controlled.service.ControlledService
import com.adbcontrol.controlled.ui.theme.AppColors
import com.adbcontrol.controlled.ui.theme.ControlledTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 被控端主入口。README 15.7 "被控端 配对" 屏幕。
 *
 * 4 步:配对(QR 扫码)→ 权限自检(MUST/OPT badge)→ 启动 Agent。
 * 当前为骨架,权限检测与扫码实际触发由 [ControlledViewModel] 提供。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            ControlledTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = AppColors.bgBase) {
                    SetupScreen(
                        onStartService = { ControlledService.start(this) },
                        onStopService = { ControlledService.stop(this) },
                    )
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }
}

@Composable
private fun SetupScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    viewModel: ControlledViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResourceSafe(R.string.app_name),
            color = AppColors.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )

        PairingCard(uiState.paired, uiState.deviceId)

        CapabilityChecklist(uiState.capabilities)

        OemKeepAliveCard()

        Spacer(modifier = Modifier.height(8.dp))

        PrimaryButton(
            text = if (uiState.serviceRunning)
                stringResourceSafe(R.string.stop_agent)
            else stringResourceSafe(R.string.start_agent),
            enabled = uiState.paired,
            onClick = { if (uiState.serviceRunning) onStopService() else onStartService() },
        )
    }
}

@Composable
private fun PairingCard(paired: Boolean, deviceId: String) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResourceSafe(R.string.pair_title),
                color = AppColors.cyan,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (paired)
                    stringResourceSafe(R.string.pair_status_paired, deviceId)
                else stringResourceSafe(R.string.pair_status_unpaired),
                color = AppColors.textPrimary,
                fontSize = 16.sp,
            )
            if (!paired) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { /* TODO: 触发 PairingScanner */ }) {
                    Text(stringResourceSafe(R.string.pair_scan_qr))
                }
            }
        }
    }
}

@Composable
private fun CapabilityChecklist(caps: List<CapabilityItem>) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResourceSafe(R.string.permission_check),
                color = AppColors.cyan,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            caps.forEach { item ->
                CapabilityRow(item)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun OemKeepAliveCard() {
    // README 3.1 L8:厂商后台保活跳转。国产 ROM 主动杀后台,需引导用户去自启管理 / 神隐模式
    // 等系统页手动加白名单。跳转失败回退应用详情(由 OemBatterySettings 内部处理)。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isMiui = OemHelper.isMiui()
    val label = when (OemHelper.oem) {
        OemHelper.Oem.XIAOMI -> "MIUI 自启动管理"
        OemHelper.Oem.HUAWEI -> "EMUI / 鸿蒙 自启动管理"
        OemHelper.Oem.OPPO -> "ColorOS 自启动管理"
        OemHelper.Oem.VIVO -> "OriginOS 后台管理"
        OemHelper.Oem.MEIZU -> "Flyme 后台管理"
        OemHelper.Oem.SAMSUNG -> "省电模式详情"
        OemHelper.Oem.OTHER -> "应用详情"
    }
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "厂商后台保活",
                color = AppColors.cyan,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "国产 ROM 会主动杀后台,请在打开的页面中允许本应用后台运行 / 自启动 / " +
                    "加入受保护名单,否则 MQTT 长连接会被断。",
                color = AppColors.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { OemBatterySettings.openAutoStart(ctx) }) {
                Text(label)
            }
            if (isMiui) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { OemBatterySettings.openMiuiGodMode(ctx) }) {
                    Text("MIUI 神隐模式 / 应用锁")
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(item: CapabilityItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(granted = item.granted)
            Spacer(modifier = Modifier.size(12.dp))
            Text(item.label, color = AppColors.textSecondary, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.badge,
                color = if (item.badge == "MUST") AppColors.magenta else AppColors.slate,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = if (item.granted) stringResourceSafe(R.string.status_granted)
                else stringResourceSafe(R.string.status_denied),
                color = if (item.granted) AppColors.emerald else AppColors.rose,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StatusDot(granted: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (granted) AppColors.emerald else AppColors.rose)
    )
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AppColors.glassBg)
            .padding(0.dp),
    ) { content() }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = Brush.linearGradient(listOf(AppColors.cyan, AppColors.magenta))
    val disabled = Brush.linearGradient(listOf(AppColors.glassBgStrong, AppColors.glassBgStrong))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) bg else disabled)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = text,
                color = if (enabled) AppColors.bgDeep else AppColors.textDisabled,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun stringResourceSafe(id: Int, vararg args: Any): String {
    // 用 LocalContext 直接取 String,避免在 Composable 调用上包 try/catch(编译器禁止)。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return runCatching { ctx.getString(id, *args) }.getOrDefault(ctx.getString(id))
}

data class CapabilityItem(val label: String, val granted: Boolean, val badge: String)
