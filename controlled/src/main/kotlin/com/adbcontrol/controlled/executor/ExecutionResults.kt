package com.adbcontrol.controlled.executor

import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.model.ExecutionResult

/**
 * 一个执行不直接支持时的失败回执常量。
 * README 3.3:"命令确实无法完成 → 失败回执 COMMAND_RESULT(success=false, error="NO_PATH")"
 */
fun noPath(commandId: String): ExecutionResult = ExecutionResult(
    commandId = commandId,
    success = false,
    output = "NO_PATH",
)

/** 构造成功回执。 */
fun ok(commandId: String, output: String = "", durationMs: Long = 0): ExecutionResult = ExecutionResult(
    commandId = commandId,
    success = true,
    output = output,
    durationMs = durationMs,
)

/** 构造失败回执(非 NO_PATH,执行过程出错)。 */
fun fail(commandId: String, error: String, durationMs: Long = 0): ExecutionResult = ExecutionResult(
    commandId = commandId,
    success = false,
    output = error,
    durationMs = durationMs,
)

/** 判断命令是否为输入类(点击/滑动/按键)。 */
fun Command.isInput(): Boolean = category == CommandCategory.INPUT

/** 判断命令是否为应用时间管理。 */
fun Command.isAppTime(): Boolean = category == CommandCategory.APP_TIME
