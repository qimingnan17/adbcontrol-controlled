package com.adbcontrol.controlled.executor

import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.ExecutionResult

/**
 * 命令执行器。README 3.3 三层执行桥接:
 *
 * 1. ShizukuExecutor  (主,无 root,shell 权限,等价 ADB)
 * 2. RootExecutor     (增强,全 ADB 能力)
 * 3. AccessibilityExecutor (兼容,无 root 无 Shizuku 时:窗口/手势/截屏/UI 拦截)
 * 4. NormalExecutor   (兜底,仅应用内 Notification/Toast/ContentResolver)
 *
 * 分派优先级与能力检测参见 [CommandDispatcher]。
 */
interface CommandExecutor {

    /** 执行器逻辑名(日志与能力上报用)。 */
    val name: String

    /** 当前是否可用(资源/权限就绪)。不可用的执行器在分派时被跳过。 */
    fun isAvailable(): Boolean

    /**
     * 该执行器是否支持某类命令。
     * [CommandDispatcher] 据此选择执行器,避免把无障碍命令误派给 Root。
     */
    fun supports(command: Command): Boolean

    /**
     * 执行命令。
     * @param command 命令体
     * @param commandId 对应 WsMessage.id,回写 [ExecutionResult.commandId]
     */
    suspend fun execute(command: Command, commandId: String): ExecutionResult
}
