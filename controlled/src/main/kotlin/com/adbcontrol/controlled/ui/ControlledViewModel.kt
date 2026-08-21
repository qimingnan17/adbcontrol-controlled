package com.adbcontrol.controlled.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.executor.CommandDispatcher
import com.adbcontrol.controlled.executor.RootExecutor
import com.adbcontrol.controlled.executor.ShizukuExecutor
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
) : ViewModel() {

    data class UiState(
        val paired: Boolean = false,
        val deviceId: String = "",
        val serviceRunning: Boolean = false,
        val capabilities: List<CapabilityItem> = emptyList(),
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
