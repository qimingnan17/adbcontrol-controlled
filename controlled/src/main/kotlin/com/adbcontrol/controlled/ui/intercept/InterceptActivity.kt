package com.adbcontrol.controlled.ui.intercept

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.adbcontrol.controlled.apptime.AppTimeController
import com.adbcontrol.controlled.apptime.ExtensionRequestManager
import com.adbcontrol.controlled.ui.theme.ControlledTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * v2.0 受限应用拦截页。
 *
 * 用户打开被暂停 / 超额 / 处于禁用时段的 App 时,由无障碍服务拉起本页(替代系统冷冰冰的"应用已暂停"弹窗):
 * - 解封时间(时段结束或明早 0 点)
 * - 今日剩余额度 / 已用时长
 * - "申请再用 N 分钟"(5 / 15 / 30 可选)→ 发送 EXTENSION_REQUEST,展示等待状态,
 *   批准后自动解封并提示可以返回使用
 *
 * 返回键 / "回到桌面" 一律回桌面,不回到受限 App。
 */
@AndroidEntryPoint
class InterceptActivity : ComponentActivity() {

    @Inject lateinit var appTimeController: AppTimeController
    @Inject lateinit var extensionRequestManager: ExtensionRequestManager

    private var pkg by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        if (pkg.isBlank()) { finish(); return }
        appTimeController.refreshNow()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            ControlledTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    InterceptScreen(
                        pkg = pkg,
                        appTimeController = appTimeController,
                        extensionRequestManager = extensionRequestManager,
                        onHome = ::goHome,
                        onOpenApp = ::openApp,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PKG)?.takeIf { it.isNotBlank() }?.let {
            pkg = it
            appTimeController.refreshNow()
        }
    }

    private fun goHome() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { startActivity(it) } }
        finish()
    }

    companion object {
        private const val EXTRA_PKG = "pkg"

        fun launch(context: Context, pkg: String) {
            val intent = Intent(context, InterceptActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            runCatching { context.startActivity(intent) }
                .onFailure { android.util.Log.w("InterceptActivity", "launch failed (后台弹出界面权限?)", it) }
        }
    }
}

private val MINUTE_OPTIONS = listOf(5, 15, 30)

@Composable
private fun InterceptScreen(
    pkg: String,
    appTimeController: AppTimeController,
    extensionRequestManager: ExtensionRequestManager,
    onHome: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val ctx = LocalContext.current
    val quotas by appTimeController.quotas.collectAsState()
    val requests by extensionRequestManager.requests.collectAsState()
    // 每 30 秒刷新一次(待审批过期 / 解封倒计时)
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    val quota = remember(pkg, quotas, tick) { quotas.firstOrNull { it.pkg == pkg } ?: appTimeController.quotaFor(pkg) }
    val req = remember(pkg, requests, tick) { extensionRequestManager.stateFor(pkg) }
    var minutes by remember { mutableIntStateOf(15) }

    val icon = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(160, 160).asImageBitmap() }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)))
        } else {
            Icon(Icons.Filled.HourglassBottom, contentDescription = null, modifier = Modifier.size(80.dp))
        }
        Text(
            text = if (quota.reason == AppTimeController.Reason.TIME_WINDOW) "现在是休息时间" else "今天的时间用完啦",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "「${quota.label}」暂时不能使用。放下手机,休息一下眼睛吧。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow(Icons.Filled.Bedtime, "解封时间", formatUnlock(quota.unlockAt))
                InfoRow(
                    Icons.Filled.HourglassBottom, "今日剩余",
                    when {
                        quota.limitMinutes == null -> "不限时长" + (quota.window?.let { "(禁用时段 $it)" } ?: "")
                        else -> "${quota.remainingMinutes ?: 0} 分钟 / 共 ${quota.limitMinutes} 分钟(已用 ${quota.usedMinutes})"
                    },
                )
                if (quota.bonusMinutes > 0) {
                    Text("今天已获批额外 ${quota.bonusMinutes} 分钟", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 申请延时
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("还需要用一会儿?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                when (req?.status) {
                    ExtensionRequestManager.Status.PENDING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text("已申请 ${req.minutes} 分钟,等待家长确认…(30 分钟内有效)")
                        }
                    }
                    ExtensionRequestManager.Status.APPROVED -> {
                        Text("✅ 已批准 ${req.grantedMinutes ?: req.minutes} 分钟,可以继续使用了")
                        Button(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) { Text("打开「${quota.label}」") }
                    }
                    else -> {
                        when (req?.status) {
                            ExtensionRequestManager.Status.DENIED -> Text("上次申请未通过。", color = MaterialTheme.colorScheme.error)
                            ExtensionRequestManager.Status.EXPIRED -> Text("上次申请已过期,可以重新申请。")
                            ExtensionRequestManager.Status.FAILED -> Text("上次申请没发出去(网络未连接),请稍后重试。", color = MaterialTheme.colorScheme.error)
                            else -> {}
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MINUTE_OPTIONS.forEach { m ->
                                FilterChip(
                                    selected = minutes == m,
                                    onClick = { minutes = m },
                                    label = { Text("$m 分钟") },
                                )
                            }
                        }
                        Button(
                            onClick = { extensionRequestManager.request(pkg, minutes) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("申请再用 $minutes 分钟") }
                    }
                }
            }
        }

        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("回到桌面") }
        Box(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun formatUnlock(at: Long?): String {
    if (at == null) return "已可使用"
    val target = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    val dayDiff = target.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR) +
        (target.get(Calendar.YEAR) - today.get(Calendar.YEAR)) * 365
    return when (dayDiff) {
        0 -> "今天 $hm"
        1 -> if (hm == "00:00") "明天 00:00(午夜重置)" else "明天 $hm"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(at))
    }
}
