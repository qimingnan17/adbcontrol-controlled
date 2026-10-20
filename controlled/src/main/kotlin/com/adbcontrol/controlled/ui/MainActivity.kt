package com.adbcontrol.controlled.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adbcontrol.controlled.R
import com.adbcontrol.controlled.apptime.AppTimeController
import com.adbcontrol.controlled.apptime.ExtensionRequestManager
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.controlled.oem.OemBatterySettings
import com.adbcontrol.controlled.service.ControlledService
import com.adbcontrol.controlled.telemetry.PowerPolicy
import com.adbcontrol.controlled.ui.theme.ControlledTheme
import com.adbcontrol.controlled.ui.theme.StatusColors
import com.adbcontrol.controlled.ui.transparency.ManagedCapabilities
import com.adbcontrol.controlled.ui.transparency.TransparencyScreen
import com.adbcontrol.controlled.ui.wizard.PermissionChecker
import com.adbcontrol.controlled.ui.wizard.PermissionWizardScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * 被控端主入口(v2.0 重设计)。
 *
 * 三个页面(轻量状态切换,不引入导航图):
 * - 首页:Material 3 动态取色大卡片 —— 连接状态 / 当前生效的管控 / 今日各受限 App 剩余时长 / 本设备受管理 / 更新
 * - 权限自检向导:首次启动自动进入,可从首页重新打开
 * - 本设备受管理:透明度页(常驻通知点击也会进入)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var openRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openRequest = intent?.getStringExtra(EXTRA_OPEN)
        setContent {
            ControlledTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(
                        initialOpen = openRequest,
                        onConsumedOpen = { openRequest = null },
                        onStartService = { ControlledService.start(this) },
                        onStopService = { ControlledService.stop(this) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest = it }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_TRANSPARENCY = "transparency"
        const val OPEN_WIZARD = "wizard"
    }
}

private enum class Page { HOME, WIZARD, TRANSPARENCY }

@Composable
private fun AppRoot(
    initialOpen: String?,
    onConsumedOpen: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    viewModel: ControlledViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    var page by rememberSaveable {
        mutableStateOf(if (PermissionChecker.isWizardDone(ctx)) Page.HOME else Page.WIZARD)
    }
    LaunchedEffect(initialOpen) {
        when (initialOpen) {
            MainActivity.OPEN_TRANSPARENCY -> page = Page.TRANSPARENCY
            MainActivity.OPEN_WIZARD -> page = Page.WIZARD
        }
        if (initialOpen != null) onConsumedOpen()
    }
    BackHandler(enabled = page != Page.HOME) { page = Page.HOME }

    val uiState by viewModel.uiState.collectAsState()
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().systemBarsPadding()) {
        when (page) {
            Page.WIZARD -> PermissionWizardScreen(
                onEnableAccessibilityViaShizuku = viewModel::forceEnableAccessibility,
                onDone = { page = Page.HOME; viewModel.refresh() },
            )
            Page.TRANSPARENCY -> TransparencyScreen(deviceId = uiState.deviceId, onBack = { page = Page.HOME })
            Page.HOME -> HomeScreen(
                viewModel = viewModel,
                onStartService = onStartService,
                onStopService = onStopService,
                onOpenWizard = { page = Page.WIZARD },
                onOpenTransparency = { page = Page.TRANSPARENCY },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    viewModel: ControlledViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenWizard: () -> Unit,
    onOpenTransparency: () -> Unit,
) {
    val ctx = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val quotas by viewModel.quotas.collectAsState()
    val powerMode by viewModel.powerMode.collectAsState()
    val requests by viewModel.extensionRequests.collectAsState()
    val running by viewModel.serviceRunning.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    var showManualPair by remember { mutableStateOf(false) }

    // 从系统设置返回时重新检测权限
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { tick++; viewModel.refreshQuotas() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permItems = remember(tick) { PermissionChecker.check(ctx) }
    val missing = permItems.filter { it.status == PermissionChecker.Status.MISSING }

    // ZXing 内嵌扫码(离线、免 GMS);结果文本交 ViewModel 解析并配对
    val qrScanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result -> if (!result.contents.isNullOrEmpty()) viewModel.pairViaQrRaw(result.contents) }
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

    // 配对成功后关闭手动对话框,并自动拉起 Agent 服务
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = ctx.getString(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (!uiState.paired) {
            PairingCard(
                pairing = uiState.pairing,
                pairError = uiState.pairError,
                onScan = launchQrScan,
                onManual = { showManualPair = true },
            )
        }
        if (showManualPair) {
            ManualPairDialog(
                busy = uiState.pairing,
                onDismiss = { showManualPair = false },
                onConfirm = { serverUrl, pairToken, deviceId -> viewModel.pairManual(serverUrl, pairToken, deviceId) },
            )
        }

        ConnectionCard(
            paired = uiState.paired,
            deviceId = uiState.deviceId,
            running = running,
            state = connection,
            powerMode = powerMode,
            onStart = onStartService,
            onStop = onStopService,
        )

        PermissionSummaryCard(missingCount = missing.size, missingTitles = missing.map { it.title }, onOpen = onOpenWizard)

        ActiveControlsCard(quotas = quotas)

        TodayRemainingCard(quotas = quotas, requests = requests)

        BigCard(
            icon = Icons.Filled.Shield,
            title = "本设备受管理",
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(ManagedCapabilities.summary(ctx), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenTransparency) { Text("查看全部管理能力") }
        }

        UpdateCard(state = updateState, currentVersion = com.adbcontrol.controlled.BuildConfig.VERSION_NAME, onCheck = viewModel::checkUpdateNow)

        if (PermissionChecker.isMiuiFamily()) {
            TextButton(onClick = { OemBatterySettings.openMiuiGodMode(ctx) }) { Text("MIUI 省电策略 / 应用权限详情") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- 卡片 ----------

@Composable
private fun BigCard(
    icon: ImageVector,
    title: String,
    container: Color,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ConnectionCard(
    paired: Boolean,
    deviceId: String,
    running: Boolean,
    state: MqttManager.ConnectionState,
    powerMode: PowerPolicy.Mode,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val (icon, label, color) = when {
        !paired -> Triple(Icons.Filled.CloudOff, "未配对", MaterialTheme.colorScheme.outline)
        state == MqttManager.ConnectionState.CONNECTED -> Triple(Icons.Filled.CloudDone, "已连接", StatusColors.ok)
        state == MqttManager.ConnectionState.CONNECTING || state == MqttManager.ConnectionState.RECONNECTING ->
            Triple(Icons.Filled.CloudSync, "连接中…", StatusColors.warn)
        else -> Triple(Icons.Filled.CloudOff, if (running) "连接断开" else "服务未运行", MaterialTheme.colorScheme.error)
    }
    BigCard(icon = icon, title = "连接状态", container = MaterialTheme.colorScheme.primaryContainer, iconTint = color) {
        Text(label, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color)
        if (paired) {
            Text("设备 ID:$deviceId", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (powerMode != PowerPolicy.Mode.NORMAL) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatterySaver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    if (powerMode == PowerPolicy.Mode.LOW_BATTERY) "省电模式:电量低于 20%,已降低定位与心跳频率"
                    else "省电模式:设备静止,已降低定位频率",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (paired) {
            if (running) OutlinedButton(onClick = onStop) { Text("停止服务") }
            else Button(onClick = onStart) { Text("启动服务") }
        }
    }
}

@Composable
private fun PermissionSummaryCard(missingCount: Int, missingTitles: List<String>, onOpen: () -> Unit) {
    val bad = missingCount > 0
    BigCard(
        icon = Icons.Filled.Security,
        title = "权限自检",
        container = if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        iconTint = if (bad) MaterialTheme.colorScheme.error else StatusColors.ok,
    ) {
        Text(
            if (bad) "$missingCount 项缺失:${missingTitles.joinToString("、")}" else "全部授权已就绪",
            style = MaterialTheme.typography.bodyMedium,
            color = if (bad) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onOpen) { Text(if (bad) "去修复" else "重新检查") }
    }
}

@Composable
private fun ActiveControlsCard(quotas: List<AppTimeController.AppQuota>) {
    val restricted = quotas.count { it.restricted }
    val limits = quotas.count { it.limitMinutes != null }
    val windows = quotas.count { it.window != null }
    BigCard(icon = Icons.Filled.Block, title = "当前生效的管控", container = MaterialTheme.colorScheme.secondaryContainer) {
        if (quotas.isEmpty()) {
            Text("暂无应用时间管控", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("$limits", "时长限制")
                Stat("$windows", "禁用时段")
                Stat("$restricted", "正在受限")
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TodayRemainingCard(
    quotas: List<AppTimeController.AppQuota>,
    requests: Map<String, ExtensionRequestManager.RequestState>,
) {
    BigCard(icon = Icons.Filled.Timer, title = "今日剩余时间", container = MaterialTheme.colorScheme.tertiaryContainer) {
        if (quotas.isEmpty()) {
            Text("没有受限的应用", style = MaterialTheme.typography.bodyMedium)
            return@BigCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            quotas.forEach { q ->
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(q.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                q.inWindowNow -> "禁用时段中"
                                q.remainingMinutes != null -> "剩 ${q.remainingMinutes} 分钟"
                                else -> "不限时长"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (q.restricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    if (q.limitMinutes != null && q.limitMinutes > 0) {
                        LinearProgressIndicator(
                            progress = { (q.usedMinutes.toFloat() / q.limitMinutes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = if (q.restricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "已用 ${q.usedMinutes} / ${q.limitMinutes} 分钟" + (if (q.bonusMinutes > 0) "(含额外 ${q.bonusMinutes})" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    q.window?.let { Text("禁用时段 $it", style = MaterialTheme.typography.bodySmall) }
                    requests[q.pkg]?.takeIf { it.status == ExtensionRequestManager.Status.PENDING }?.let {
                        Text("延时申请 ${it.minutes} 分钟 · 等待家长确认", style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.warn)
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingCard(
    pairing: Boolean,
    pairError: String?,
    onScan: () -> Unit,
    onManual: () -> Unit,
) {
    BigCard(icon = Icons.Filled.QrCode, title = "设备配对", container = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("扫描 Web 控制台的配对二维码,或手动输入配对码。", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan, enabled = !pairing) { Text(if (pairing) "配对中…" else "扫描配对二维码") }
            OutlinedButton(onClick = onManual, enabled = !pairing) { Text("手动输入") }
        }
        pairError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 手动配对:国行 ROM 无 Google Play Services 时走文本输入兜底。 */
@Composable
private fun ManualPairDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (serverUrl: String, pairToken: String, deviceId: String) -> Unit,
) {
    var serverUrl by remember { mutableStateOf(DEFAULT_SERVER_URL) }
    var pairToken by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("手动输入配对码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("在 Web 控制台「令牌管理」生成令牌后,将以下三项复制到本机输入。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("服务器地址") },
                    singleLine = true, enabled = !busy)
                OutlinedTextField(value = pairToken, onValueChange = { pairToken = it }, label = { Text("配对令牌 (pt_ 开头)") },
                    singleLine = true, enabled = !busy)
                OutlinedTextField(value = deviceId, onValueChange = { deviceId = it }, label = { Text("设备 ID (dev_ 开头)") },
                    singleLine = true, enabled = !busy)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(serverUrl, pairToken, deviceId) },
                enabled = !busy && serverUrl.isNotBlank() && pairToken.isNotBlank() && deviceId.isNotBlank(),
            ) { Text(if (busy) "配对中…" else "配对") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

/** 手动配对默认服务器地址;扫码路径的 serverUrl 来自 QR 载荷,不受此影响。 */
private const val DEFAULT_SERVER_URL = "https://adbcontrol-backend.fly.dev"

/** OTA 更新卡片:展示当前版本、检查/下载/安装进度与手动触发入口。 */
@Composable
private fun UpdateCard(
    state: com.adbcontrol.controlled.update.UpdateRunner.UpdateUiState,
    currentVersion: String,
    onCheck: () -> Unit,
) {
    BigCard(icon = Icons.Filled.SystemUpdate, title = "应用更新", container = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("当前版本 v$currentVersion", style = MaterialTheme.typography.bodySmall)
        if (state.statusText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.progress >= 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onCheck, enabled = !state.busy) { Text(if (state.busy) "处理中…" else "立即检查更新") }
    }
}

/** 旧版权限清单数据结构(ViewModel 仍在构建,保留以兼容)。 */
data class CapabilityItem(
    val label: String,
    val granted: Boolean,
    val badge: String,
    val action: ((android.content.Context) -> Unit)? = null,
)
