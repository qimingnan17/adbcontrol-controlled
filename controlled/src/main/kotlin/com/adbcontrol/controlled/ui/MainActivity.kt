package com.adbcontrol.controlled.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var showManualPair by remember { mutableStateOf(false) }

    // ZXing 内嵌扫码(离线、免 GMS);结果文本交 ViewModel 解析并配对
    val qrScanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        if (!result.contents.isNullOrEmpty()) {
            viewModel.pairViaQrRaw(result.contents)
        }
    }
    val launchQrScan = {
        qrScanLauncher.launch(
            com.journeyapps.barcodescanner.ScanOptions().apply {
                setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                setPrompt("将 Web 控制台的配对二维码对准取景框")
                setBeepEnabled(false)
                setOrientationLocked(true)
            }
        )
    }

    // 配对成功后:关闭手动输入对话框,并自动拉起 Agent 服务(服务可能在配对前已 idle 启动,
    // 会经 onStartCommand 重载配置连 MQTT)
    var wasPaired by remember { mutableStateOf(uiState.paired) }
    LaunchedEffect(uiState.paired) {
        if (uiState.paired) {
            showManualPair = false
            if (!wasPaired) onStartService()
        }
        wasPaired = uiState.paired
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResourceSafe(R.string.app_name),
            color = AppColors.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )

        PairingCard(
            paired = uiState.paired,
            deviceId = uiState.deviceId,
            pairing = uiState.pairing,
            pairError = uiState.pairError,
            onScan = launchQrScan,
            onManual = { showManualPair = true },
        )

        if (showManualPair) {
            ManualPairDialog(
                busy = uiState.pairing,
                onDismiss = { showManualPair = false },
                onConfirm = { serverUrl, pairToken, deviceId ->
                    viewModel.pairManual(serverUrl, pairToken, deviceId)
                },
            )
        }

        CapabilityChecklist(
            caps = uiState.capabilities,
            a11yMsg = uiState.a11yMsg,
            onEnableAccessibility = viewModel::forceEnableAccessibility,
        )

        // OTA 更新卡片:当前版本 + 流程状态 + 手动检查
        val updateState by viewModel.updateState.collectAsState()
        UpdateCard(
            state = updateState,
            currentVersion = com.adbcontrol.controlled.BuildConfig.VERSION_NAME,
            onCheck = viewModel::checkUpdateNow,
        )

        // 无障碍已授予时展示系统快捷方式开关(音量键长按 / 悬浮按钮入口)
        uiState.shortcutEnabled?.let { shortcutEnabled ->
            AccessibilityShortcutCard(
                enabled = shortcutEnabled,
                busy = uiState.shortcutBusy,
                msg = uiState.shortcutMsg,
                onToggle = viewModel::toggleAccessibilityShortcut,
            )
        }

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
private fun PairingCard(
    paired: Boolean,
    deviceId: String,
    pairing: Boolean,
    pairError: String?,
    onScan: () -> Unit,
    onManual: () -> Unit,
) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onScan, enabled = !pairing) {
                        Text(if (pairing) "配对中…" else stringResourceSafe(R.string.pair_scan_qr))
                    }
                    Button(onClick = onManual, enabled = !pairing) {
                        Text("手动输入配对码")
                    }
                }
            }
            pairError?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = AppColors.rose,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 手动配对:国行 ROM 无 Google Play Services 时 ML Kit 扫码不可用,走文本输入兜底。 */
@Composable
private fun ManualPairDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (serverUrl: String, pairToken: String, deviceId: String) -> Unit,
) {
    var serverUrl by remember { mutableStateOf(DEFAULT_SERVER_URL) }
    var pairToken by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("手动输入配对码", color = AppColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "在 Web 控制台「令牌管理」生成令牌后,将以下三项复制到本机输入。",
                    color = AppColors.textSecondary,
                    fontSize = 12.sp,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    enabled = !busy,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = pairToken,
                    onValueChange = { pairToken = it },
                    label = { Text("配对令牌 (pt_ 开头)") },
                    singleLine = true,
                    enabled = !busy,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("设备 ID (dev_ 开头)") },
                    singleLine = true,
                    enabled = !busy,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(serverUrl, pairToken, deviceId) },
                enabled = !busy && serverUrl.isNotBlank() && pairToken.isNotBlank() && deviceId.isNotBlank(),
            ) {
                Text(if (busy) "配对中…" else "配对")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** 手动配对默认服务器地址;扫码路径的 serverUrl 来自 QR 载荷,不受此影响。 */
private const val DEFAULT_SERVER_URL = "https://adbcontrol-backend.fly.dev"

@Composable
private fun CapabilityChecklist(
    caps: List<CapabilityItem>,
    a11yMsg: String?,
    onEnableAccessibility: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    // 从系统设置授权返回(ON_RESUME)时递增 tick,触发重新检测
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 叠加系统级真实检测(无障碍/设备管理/使用情况/通知监听/电池白名单),并挂接跳转动作;
    // 无障碍服务的动作覆盖为"一键开启"(Shizuku 直写设置,绕开只有快捷方式的界面)
    val items = remember(caps, tick) {
        caps.map { it.resolved(ctx) }.map { item ->
            if (item.label == "无障碍服务") item.copy(action = { onEnableAccessibility() })
            else item
        }
    }

    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResourceSafe(R.string.permission_check),
                color = AppColors.cyan,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            items.forEach { item ->
                CapabilityRow(item)
                if (item.label == "无障碍服务") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = (a11yMsg ?: "在“已安装的服务”列表里打开本应用的无障碍开关（不是快捷方式）"),
                        color = AppColors.slate,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** 用系统真实状态覆盖硬编码 false 的检测项,并按权限类型挂"去授权"跳转。 */
private fun CapabilityItem.resolved(ctx: android.content.Context): CapabilityItem {
    val granted: Boolean? = when (label) {
        "无障碍服务" -> {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabled.contains("ControlledAccessibilityService")
        }
        "设备管理" -> {
            val dpm = ctx.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            dpm.isAdminActive(
                android.content.ComponentName(ctx, com.adbcontrol.controlled.admin.ControlledDeviceAdminReceiver::class.java)
            )
        }
        "使用情况访问" -> {
            val appOps = ctx.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName,
            ) == android.app.AppOpsManager.MODE_ALLOWED
        }
        "电池白名单" -> {
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }
        else -> null
    }
    val action: ((android.content.Context) -> Unit)? = when (label) {
        "Shizuku 桥接" -> { c ->
            // 已安装则拉起 Shizuku 授权;未安装则跳应用商店页
            c.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
                runCatching { c.startActivity(it) }
            } ?: runCatching {
                c.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api")
                    )
                )
            }
        }
"无障碍服务" -> { c ->
            // 深链定位到本服务详情页:部分 ROM 支持通过 extra 直接跳到目标服务的无障碍开关页
            val deepLink = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(
                    "android.intent.extra.COMPONENT_NAME",
                    android.content.ComponentName(
                        c, com.adbcontrol.controlled.accessibility.ControlledAccessibilityService::class.java
                    ).flattenToString(),
                )
            }
runCatching { c.startActivity(deepLink) }
                .onFailure { runCatching { c.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        }
        "设备管理" -> { c ->
            runCatching {
                c.startActivity(
                    android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(
                            android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            android.content.ComponentName(c, com.adbcontrol.controlled.admin.ControlledDeviceAdminReceiver::class.java),
                        )
                        putExtra(
                            android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "激活设备管理以支持远程锁屏等控制指令",
                        )
                    }
                )
            }
        }
        "使用情况访问" -> { c ->
            runCatching { c.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        "电池白名单" -> { c ->
            runCatching {
                c.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${c.packageName}"),
                    )
                )
            }
        }
        else -> null
    }
    return copy(granted = granted ?: this.granted, action = action)
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

/** OTA 更新卡片:展示当前版本、检查/下载/安装进度与手动触发入口。 */
@Composable
private fun UpdateCard(
    state: com.adbcontrol.controlled.update.UpdateRunner.UpdateUiState,
    currentVersion: String,
    onCheck: () -> Unit,
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "应用更新",
                color = AppColors.cyan,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "当前版本 v$currentVersion",
                color = AppColors.textSecondary,
                fontSize = 12.sp,
            )
            if (state.statusText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.statusText,
                    color = if (state.lastError != null) AppColors.rose else AppColors.textSecondary,
                    fontSize = 12.sp,
                )
            }
            if (state.progress >= 0) {
                Spacer(modifier = Modifier.height(8.dp))
                @Suppress("DEPRECATION")
                androidx.compose.material3.LinearProgressIndicator(
                    progress = state.progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onCheck, enabled = !state.busy) {
                Text(if (state.busy) "处理中…" else "立即检查更新")
            }
        }
    }
}

/** 系统无障碍快捷方式开关卡(授予无障碍后出现;写入依赖 WRITE_SECURE_SETTINGS 或 Shizuku)。 */
@Composable
private fun AccessibilityShortcutCard(
    enabled: Boolean,
    busy: Boolean,
    msg: String?,
    onToggle: (Boolean) -> Unit,
) {
    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "无障碍快捷方式",
                        color = AppColors.textPrimary,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "控制音量键长按 / 悬浮按钮是否唤起本应用的无障碍服务",
                        color = AppColors.textSecondary,
                        fontSize = 11.sp,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = enabled,
                    enabled = !busy,
                    onCheckedChange = { onToggle(it) },
                )
            }
            msg?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = it, color = AppColors.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CapabilityRow(item: CapabilityItem) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
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
            if (!item.granted && item.action != null) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "去授权",
                    color = AppColors.cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { item.action?.invoke(ctx) },
                )
            }
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

data class CapabilityItem(
    val label: String,
    val granted: Boolean,
    val badge: String,
    /** 未授权时的"去授权"跳转动作(打开对应系统设置页);null 表示无可引导入口(如 Root) */
    val action: ((android.content.Context) -> Unit)? = null,
)
