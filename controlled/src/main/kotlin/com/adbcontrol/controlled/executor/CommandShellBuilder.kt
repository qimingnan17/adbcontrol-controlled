package com.adbcontrol.controlled.executor

import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory

/**
 * 将 [Command] 翻译为等价 ADB/shell 命令。
 *
 * Shizuku 与 Root 执行器共享此逻辑,差异只在执行进程上下文。
 * README 3.3 能力矩阵对齐。
 *
 * 安全:所有从 [Command.params] 插值到 shell 字符串的值都需通过白名单校验,
 * 防 shell 注入(README 8.2)。包名走 [validPkg],数字参数走 [validInt]/[validKeyCode]。
 * **不再透传任意 shell**:FILE/shell 动作仅允许只读诊断白名单(dumpsys/getprop/pm list/ps),
 * 任何带写操作或管道/重定向的命令一律拒绝(防 RCE)。
 */
object CommandShellBuilder {

    /** Android 包名合法字符:[A-Za-z0-9._],至少一段。 */
    private val PKG_REGEX = Regex("^[A-Za-z][A-Za-z0-9_.]*$")

    /** 整数(允许负号用于亮度/模式等)。 */
    private val INT_REGEX = Regex("^-?[0-9]+$")

    /** 整数坐标 / 距离(非负)。 */
    private val COORD_REGEX = Regex("^[0-9]+$")

    /** input keyevent 接受:纯数字或 KEYCODE_* / 厂商自定义 *_KEYCODE(白名单字符)。 */
    private val KEYEVENT_REGEX = Regex("^[A-Z0-9_]+$")

    /**
     * FILE/shell 透传白名单:仅允许只读诊断命令前缀,禁止任何写操作 / 管道 / 重定向 / 后台符。
     * 防止主控端被攻破后通过 shell 动作 RCE 被控端。
     */
    private val SAFE_SHELL_PREFIXES = setOf(
        "dumpsys", "getprop", "pm list", "pm path", "pm dump",
        "ps", "top -n 1", "df", "free", "mount", "ls", "cat /proc/",
        "settings get", "getenforce", "id", "uptime",
    )

    /** 危险字符:出现即拒绝 shell 透传(管道 / 重定向 / 命令分隔 / 后台 / 转义)。 */
    private val SHELL_DANGEROUS = Regex("[;&|`$()<>\\\\\\n\\r]")

    private fun validSafeShell(cmd: String?): String? {
        val s = cmd?.trim() ?: return null
        if (SHELL_DANGEROUS.containsMatchIn(s)) return null
        return SAFE_SHELL_PREFIXES.firstOrNull { s == it || s.startsWith("$it ") }?.let { s }
    }

    /** 包名校验,非法返回 null(由上层返回 NO_PATH)。 */
    private fun validPkg(s: String?): String? =
        s?.takeIf { PKG_REGEX.matches(it) }

    /** 非负整数校验(坐标 / 距离 / 时长)。 */
    private fun validCoord(s: String?): String? =
        s?.takeIf { COORD_REGEX.matches(it) }

    /** 整数校验(可负,亮度 / 模式)。 */
    private fun validInt(s: String?): String? =
        s?.takeIf { INT_REGEX.matches(it) }

    /** keyevent 参数校验。 */
    private fun validKeyCode(s: String?): String? =
        s?.takeIf { KEYEVENT_REGEX.matches(it) }

    /** 构建单条 shell 命令字符串。返回 null 表示该命令无法用 shell 表达(应交给无障碍层)。 */
    fun build(command: Command): String? = when (command.category) {
        CommandCategory.INPUT -> buildInput(command)
        CommandCategory.APP -> buildApp(command)
        CommandCategory.SYSTEM -> buildSystem(command)
        CommandCategory.FILE -> buildFile(command)
        CommandCategory.APP_TIME -> buildAppTime(command)
    }

    private fun buildInput(c: Command): String? = when (c.action) {
        "tap" -> {
            val x = validCoord(c.params["x"]) ?: return null
            val y = validCoord(c.params["y"]) ?: return null
            "input tap $x $y"
        }
        "swipe" -> {
            val x1 = validCoord(c.params["x1"]) ?: return null
            val y1 = validCoord(c.params["y1"]) ?: return null
            val x2 = validCoord(c.params["x2"]) ?: return null
            val y2 = validCoord(c.params["y2"]) ?: return null
            val ms = validCoord(c.params["durationMs"]) ?: "300"
            "input swipe $x1 $y1 $x2 $y2 $ms"
        }
        "keyevent" -> {
            val code = validKeyCode(c.params["code"]) ?: return null
            "input keyevent $code"
        }
        "text" -> {
            val text = c.params["text"] ?: return null
            // 转义单引号
            val safe = text.replace("'", "'\\''")
            "input text '$safe'"
        }
        else -> null
    }

    private fun buildApp(c: Command): String? = when (c.action) {
        "forceStop" -> {
            val pkg = validPkg(c.params["pkg"]) ?: return null
            "am force-stop $pkg"
        }
        "start" -> {
            val pkg = validPkg(c.params["pkg"]) ?: return null
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
        }
        "install" -> null // 走 R2 旁路下载 + pm install,见 SelfHostedUpdateChannel / CommandDispatcher 扩展
        "uninstall" -> {
            val pkg = validPkg(c.params["pkg"]) ?: return null
            "pm uninstall $pkg"
        }
        "clear" -> {
            val pkg = validPkg(c.params["pkg"]) ?: return null
            "pm clear $pkg"
        }
        "screencap" -> null // 截屏走 Shizuku 直接执行 / 无障碍 takeScreenshot,不在此拼字符串
        else -> null
    }

    private fun buildSystem(c: Command): String? = when (c.action) {
        "setBrightness" -> {
            val v = validInt(c.params["value"]) ?: return null
            "settings put system screen_brightness $v"
        }
        "setBrightnessMode" -> {
            val v = validInt(c.params["value"]) ?: return null
            "settings put system screen_brightness_mode $v"
        }
        "setVolume" -> null // 走 AudioManager(NormalExecutor)更稳
        "lockScreen" -> "input keyevent 26"
        "unlockScreen" -> "input keyevent 82" // MENU 唤醒
        "reboot" -> "reboot"
        "powerOff" -> "reboot -p"
        else -> null
    }

    private fun buildFile(c: Command): String? = when (c.action) {
        "shell" -> validSafeShell(c.params["cmd"]) // 仅放行只读诊断白名单,防 RCE
        "pull" -> null // 文件传输走 R2 旁路
        "push" -> null
        else -> null
    }

    private fun buildAppTime(c: Command): String? = when (c.action) {
        "suspend" -> {
            val pkg = validPkg(c.params["pkg"]) ?: return null
            "am suspend $pkg"
        }
        "unsuspend" -> {
            val pkg = validPkg(c.params["pkg"]) ?: return null
            "am unsuspend $pkg"
        }
        "disable" -> {
            val pkg = validPkg(c.params["pkg"]) ?: return null
            "pm disable-user --user 0 $pkg"
        }
        "enable" -> {
            val pkg = validPkg(c.params["pkg"]) ?: return null
            "pm enable $pkg"
        }
        else -> null
    }

    /** 判断命令是否可用 shell 表达(供 supports() 使用)。 */
    fun isShellExpressible(command: Command): Boolean = build(command) != null ||
        (command.category == CommandCategory.APP && command.action == "screencap")
}
