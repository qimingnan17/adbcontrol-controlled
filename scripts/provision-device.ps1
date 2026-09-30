# 被控端设备一键配置(provision)脚本
# 用途:卸载重装 APK 后,把能用 adb 自动授予的权限全部自动配平,
#       免去逐个进系统设置的手动操作。最后打印仍需手动处理的少量项。
#
# 用法:
#   .\provision-device.ps1 [-Serial emulator-5554] [-Apk <apk路径>]
#   不带 -Apk 则跳过安装只做授权;不带 -Serial 则取第一台在线设备。
#
# 原理备忘:
#   - `install -g`:安装时自动授予全部运行时权限(通知/相机/定位);
#   - PACKAGE_USAGE_STATS 属 appops,须 `appops set`;
#   - 无障碍服务可直接写 settings secure 的 enabled_accessibility_services
#     (adb shell 自带 WRITE_SECURE_SETTINGS 特权),与既有服务列表做合并而非覆盖;
#   - 电池白名单走 `dumpsys deviceidle whitelist +<pkg>`;
#   - 设备管理员 / MIUI 自启动 / Shizuku 对本 App 的授权弹窗无法纯 adb 静默完成,
#     脚本末尾会列出人工步骤。
param(
    [string]$Serial = "",
    [string]$Apk = ""
)

$ErrorActionPreference = "Stop"
$Pkg = "com.adbcontrol.controlled"
$A11ySvc = "$Pkg/com.adbcontrol.controlled.accessibility.ControlledAccessibilityService"

function Adb([string[]]$Args) {
    if ($Serial) { adb -s $Serial @Args } else { adb @Args }
}

# ---- 选设备 ---------------------------------------------------------------
if (-not $Serial) {
    $line = (adb devices | Select-String "device`t") | Select-Object -First 1
    if (-not $line) { Write-Host "没有在线设备"; exit 1 }
    $Serial = ($line -split "\s+")[0]
}
Write-Host "== 目标设备: $Serial =="

# ---- 安装(-g 自动授运行时权限) -------------------------------------------
if ($Apk) {
    Write-Host "-- 安装 APK(含 -g 自动授权)"
    Adb @("install", "-g", "-r", $Apk)
} else {
    Write-Host "-- 未指定 -Apk,跳过安装"
}

# ---- 应用使用时长(appops) -------------------------------------------------
Write-Host "-- PACKAGE_USAGE_STATS(appops)"
Adb @("shell", "appops", "set", $Pkg, "PACKAGE_USAGE_STATS", "allow")

# ---- 电池优化白名单 ---------------------------------------------------------
Write-Host "-- 电池优化白名单"
Adb @("shell", "dumpsys", "deviceidle", "whitelist", "+$Pkg") | Out-Null

# ---- 后台定位(部分系统 -g 不会给 BG,补一刀) ------------------------------
Write-Host "-- ACCESS_BACKGROUND_LOCATION"
Adb @("shell", "pm", "grant", $Pkg, "android.permission.ACCESS_BACKGROUND_LOCATION") 2>$null

# ---- 无障碍服务(合并写入,不破坏其他服务) ---------------------------------
Write-Host "-- 无障碍服务"
$existing = (Adb @("shell", "settings", "get", "secure", "enabled_accessibility_services")).Trim()
if ($existing -and $existing -ne "null" -and $existing -notlike "*$Pkg/*") {
    $new = "$existing`:$A11ySvc"
} else {
    $new = $A11ySvc
}
Adb @("shell", "settings", "put", "secure", "enabled_accessibility_services", $new)
Adb @("shell", "settings", "put", "secure", "accessibility_enabled", "1")

# ---- Shizuku server(adbd 权限拉起) ----------------------------------------
Write-Host "-- 尝试拉起 Shizuku server"
Adb @("shell", "sh", "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh") 2>$null

# ---- 校验 -------------------------------------------------------------------
Write-Host "`n== 校验 =="
$a11y = (Adb @("shell", "settings", "get", "secure", "enabled_accessibility_services")).Trim()
Write-Host ("无障碍已启用 : {0}" -f ($a11y -like "*$Pkg*"))
$whitelist = (Adb @("shell", "dumpsys", "deviceidle", "whitelist")) -match [regex]::Escape($Pkg)
Write-Host ("电池白名单   : {0}" -f ([bool]$whitelist))
$perm = (Adb @("shell", "dumpsys", "package", $Pkg)) -match "ACCESS_BACKGROUND_LOCATION.*granted=true"
Write-Host ("后台定位     : {0}" -f ([bool]($perm -ne $null)))

Write-Host @"
`n== 仍需手动(adb 无法静默完成) ==
1. 设备管理员:App 内触发激活页 → 点「激活」(锁屏/防卸载用)
2. Shizuku 对本 App 授权:首次调用时弹窗点「允许」(仅重装后一次)
3. 国产 ROM 自启动:MIUI 等「自启动」开关需在系统设置里打开
"@
