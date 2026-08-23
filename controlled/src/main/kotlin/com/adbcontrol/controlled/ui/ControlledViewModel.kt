package com.adbcontrol.controlled.ui

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbcontrol.controlled.accessibility.AccessibilityShortcutController
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.config.PairingClient
import com.adbcontrol.controlled.config.PairingScanner
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.executor.RootExecutor
import com.adbcontrol.controlled.executor.ShizukuExecutor
import com.adbcontrol.controlled.update.UpdateRunner
import com.adbcontrol.shared.model.AppConfig
import com.adbcontrol.shared.model.PairTokenPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ControlledViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val configStore: ConfigStore,
    private val dispatcher: CommandDispatcher,
    private val shizukuExecutor: ShizukuExecutor,
    private val rootExecutor: RootExecutor,
    private val pairingScanner: PairingScanner,
    private val pairingClient: PairingClient,
    private val updateRunner: UpdateRunner,
) : ViewModel() {

    /** OTA 更新流程状态(供"应用更新"卡片实时展示)。 */
    val updateState: kotlinx.coroutines.flow.StateFlow<UpdateRunner.UpdateUiState> = updateRunner.state

    /** 手动触发一次 检查→下载→静默安装 流程。 */
    fun checkUpdateNow() = updateRunner.trigger("manual")

    /** 无障碍快捷方式开关控制器(懒构造,依赖 Shizuku 状态判断)。 */
    private val shortcutController by lazy {
        AccessibilityShortcutController(appContext, shizukuExecutor)
    }

    data class UiState(
        val paired: Boolean = false,
        val deviceId: String = "",
        val serviceRunning: Boolean = false,
        val capabilities: List<CapabilityItem> = emptyList(),
        /** 配对请求进行中(扫码或手动) */
        val pairing: Boolean = false,
        /** 最近一次配对失败原因,展示在配对卡片中 */
        val pairError: String? = null,
        /** 系统无障碍快捷方式是否已登记本应用(null=未知) */
        val shortcutEnabled: Boolean? = null,
        /** 快捷方式切换结果提示(null=无) */
        val shortcutMsg: String? = null,
        /** 快捷方式切换进行中 */
        val shortcutBusy: Boolean = false,
        /** 一键开启无障碍结果提示(null=无) */
        val a11yMsg: String? = null,
        /** 一键开启无障碍进行中 */
        val a11yBusy: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        // C14:viewModelScope.launch 默认派发 Dispatchers.Main.immediate,而
        // configStore.load()(文件 IO)与 buildCapabilityList() 内 rootExecutor.isAvailable()
        // (spawn su -c id 子进程,readText 阻塞)在主线程上会卡死数秒(无 root 或 su 卡密码时 ANR)。
        // 整体切到 IO 池,UI 状态回写切回 Main。
        viewModelScope.launch(Dispatchers.IO) {
            val config = configStore.load()
            val caps = buildCapabilityList()
            val shortcutEnabled = if (isAccessibilityGranted()) shortcutController.isEnabled() else null
            val state = UiState(
                paired = config != null,
                deviceId = config?.deviceId.orEmpty(),
                serviceRunning = false, // TODO: 观察 ControlledService 生命周期
                capabilities = caps,
                shortcutEnabled = shortcutEnabled,
            )
            withContext(Dispatchers.Main) {
                // 保留用户刚触发的 busy/msg 状态,只刷新数据字段
                _uiState.value = state.copy(
                    shortcutBusy = _uiState.value.shortcutBusy,
                    shortcutMsg = _uiState.value.shortcutMsg,
                )
            }
        }
    }

    /** 切换系统无障碍快捷方式;完成后刷新状态并给出结果提示。 */
    fun toggleAccessibilityShortcut(enable: Boolean) {
        if (_uiState.value.shortcutBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(shortcutBusy = true, shortcutMsg = null) }
            val err = runCatching { shortcutController.setEnabled(enable) }
                .getOrElse { "操作失败: ${it.message}" }
            val nowEnabled = runCatching { shortcutController.isEnabled() }.getOrDefault(enable)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    shortcutBusy = false,
                    shortcutEnabled = nowEnabled,
                    shortcutMsg = err ?: (if (enable) "已开启" else "已关闭"),
                )
            }
        }
    }

    /**
     * 一键开启无障碍服务(绕过国产 ROM 只有快捷方式的界面)。
     * 优先 Shizuku 直接写 secure 设置;Shizuku 不可用时回退跳系统无障碍设置页。
     */
    fun forceEnableAccessibility() {
        if (_uiState.value.a11yBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(a11yBusy = true, a11yMsg = null) }
            val component = android.content.ComponentName(
                appContext, com.adbcontrol.controlled.accessibility.ControlledAccessibilityService::class.java
            ).flattenToString()

            if (shizukuExecutor.isAvailable()) {
                // Shizuku 直写:enabled_accessibility_services 用冒号分隔的 flatten component 列表
                val ok1 = runCatching {
                    shizukuExecutor.execShell("settings put secure enabled_accessibility_services $component", "a11y-enable")
                }.getOrNull()?.success == true
                val ok2 = runCatching {
                    shizukuExecutor.execShell("settings put secure accessibility_enabled 1", "a11y-enable")
                }.getOrNull()?.success == true
                val granted = isAccessibilityGranted()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        a11yBusy = false,
                        a11yMsg = when {
                            granted -> "无障碍已开启"
                            ok1 && ok2 -> "已写入设置，稍等系统生效"
                            else -> "写入失败：请确认 Shizuku 已授权后重试"
                        },
                    )
                }
            } else {
                // 无 Shizuku:回退系统设置页(兼容 MIUI 深链)
                val deepLink = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    putExtra("android.intent.extra.COMPONENT_NAME", component)
                }
                withContext(Dispatchers.Main) {
                    runCatching { appContext.startActivity(deepLink.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { runCatching { appContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    _uiState.value = _uiState.value.copy(
                        a11yBusy = false,
                        a11yMsg = "已跳转设置，请在“已安装的服务”中打开开关",
                    )
                }
            }
            refresh()
        }
    }

    private fun isAccessibilityGranted(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            appContext.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.contains(ControlledAccessibilityServiceFlag)
    }

    /** 扫码结果配对:相机界面由 zxing CaptureActivity 承担(见 MainActivity),此处只消费扫码文本。 */
    fun pairViaQrRaw(raw: String) {
        if (_uiState.value.pairing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pairing = true, pairError = null)
            try {
                val payload = try {
                    pairingScanner.parse(raw)
                } catch (e: Exception) {
                    throw IllegalStateException("二维码内容不是有效的配对载荷")
                }
                performPair(payload)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(pairing = false, pairError = "配对失败:${friendly(e)}")
            }
        }
    }

    /** 手动配对:三项均来自 Web 控制台「令牌管理」生成结果。 */
    fun pairManual(serverUrl: String, pairToken: String, deviceId: String) {
        if (_uiState.value.pairing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pairing = true, pairError = null)
            try {
                performPair(
                    PairTokenPayload(
                        pairToken = pairToken.trim(),
                        serverUrl = serverUrl.trim(),
                        deviceId = deviceId.trim(),
                        deviceName = null,
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(pairing = false, pairError = "配对失败:${friendly(e)}")
            }
        }
    }

    private suspend fun performPair(payload: PairTokenPayload) {
        // PairingClient.pair 内部已切 IO;configStore.save 走 EncryptedFile 磁盘 IO,单独切池
        val response = pairingClient.pair(payload, Build.MODEL)
        val config = AppConfig(
            deviceId = payload.deviceId,
            broker = response.broker,
            r2 = response.r2,
            sessionKey = response.sessionKey,
            expiresAt = response.expiresAt,
            serverUrl = payload.serverUrl,
        )
        withContext(Dispatchers.IO) { configStore.save(config) }
        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                pairing = false,
                pairError = null,
                paired = true,
                deviceId = config.deviceId,
            )
        }
    }

    /** 把 PairingClient 抛出的 "server returned 400: {"code":..,"message":".."}" 提炼成可读文案。 */
    private fun friendly(e: Exception): String {
        val raw = e.message ?: e.javaClass.simpleName
        val m = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(raw)
        return m?.groupValues?.get(1) ?: raw
    }

    private fun buildCapabilityList(): List<CapabilityItem> {
        return listOf(
            CapabilityItem("Shizuku 桥接", shizukuExecutor.isAvailable(), "MUST"),
            CapabilityItem("Root(增强)", rootExecutor.isAvailable(), "OPT"),
            CapabilityItem("无障碍服务", isAccessibilityGranted(), "OPT"),
            CapabilityItem("设备管理", false, "OPT"),
            CapabilityItem("使用情况访问", false, "MUST"),
            CapabilityItem("电池白名单", false, "MUST"),
        )
    }
}

/** ENABLED_ACCESSIBILITY_SERVICES 里本应用无障碍服务的组件片段(与 MainActivity 检测一致)。 */
const val ControlledAccessibilityServiceFlag = "ControlledAccessibilityService"
