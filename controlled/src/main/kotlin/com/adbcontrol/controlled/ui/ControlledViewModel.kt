package com.adbcontrol.controlled.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.config.PairingClient
import com.adbcontrol.controlled.config.PairingScanner
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.executor.RootExecutor
import com.adbcontrol.controlled.executor.ShizukuExecutor
import com.adbcontrol.shared.model.AppConfig
import com.adbcontrol.shared.model.PairTokenPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ControlledViewModel @Inject constructor(
    private val configStore: ConfigStore,
    private val dispatcher: CommandDispatcher,
    private val shizukuExecutor: ShizukuExecutor,
    private val rootExecutor: RootExecutor,
    private val pairingScanner: PairingScanner,
    private val pairingClient: PairingClient,
) : ViewModel() {

    data class UiState(
        val paired: Boolean = false,
        val deviceId: String = "",
        val serviceRunning: Boolean = false,
        val capabilities: List<CapabilityItem> = emptyList(),
        /** 配对请求进行中(扫码或手动) */
        val pairing: Boolean = false,
        /** 最近一次配对失败原因,展示在配对卡片中 */
        val pairError: String? = null,
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
            val state = UiState(
                paired = config != null,
                deviceId = config?.deviceId.orEmpty(),
                serviceRunning = false, // TODO: 观察 ControlledService 生命周期
                capabilities = caps,
            )
            withContext(Dispatchers.Main) {
                _uiState.value = state
            }
        }
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
            CapabilityItem("无障碍服务", false, "OPT"), // TODO: 检测 AccessibilityServiceBridge.isConnected()
            CapabilityItem("设备管理", false, "OPT"),
            CapabilityItem("使用情况访问", false, "MUST"),
            CapabilityItem("通知监听", false, "OPT"),
            CapabilityItem("电池白名单", false, "MUST"),
        )
    }
}
