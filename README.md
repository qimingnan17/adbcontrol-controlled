# adbcontrol-controlled

> **AdbControl Android 受控端 Agent**：基于 **Kotlin + Jetpack Compose + Hilt** 打造的高鲁棒性 Android 终端管理守护应用。适配 Android 11+（API 30+，深度兼容小米 MIUI / HyperOS），集成 5 级自适应特权执行架构、应用使用时长与时段防沉迷管控、交互式任务栏通知签收闭环、全维度系统遥测采集及自更新容灾机制。

[![Android](https://img.shields.io/badge/Android-11+_(API_30+)-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com/)
[![Compose](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Hilt](https://img.shields.io/badge/DI-Dagger_Hilt-black.svg)](https://dagger.dev/hilt/)
[![Shizuku](https://img.shields.io/badge/Bridge-Shizuku_API-00B0FF.svg)](https://shizuku.rikka.app/)
[![MQTT](https://img.shields.io/badge/Transport-Paho_MQTT_TLS-009966.svg?logo=mqtt&logoColor=white)](https://www.eclipse.org/paho/)

---

## 📱 核心功能与模块

### 1. 5 层自适应命令执行引擎（[`CommandDispatcher`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/executor/CommandDispatcher.kt)）
被控端采用责任链架构，下发指令时根据命令类型与设备当前授权状态自动选取最合适的执行通道：
- **[`ShizukuExecutor`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/executor/ShizukuExecutor.kt)（主力 ADB 特权通道）**：
  - 核心执行机制，免 Root 即可直接调用 Android 系统底层 ADB 级服务；
  - 支持执行 `screencap`（静默截图）、`am force-stop`、`pm install/uninstall`、`input tap/swipe/keyevent` 及应用包挂起等高权限操作。
  - **截图回传闭环**：[`CommandHandler`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/net/CommandHandler.kt) 在此基础上补齐回传链路——Shizuku `screencap -p` 取 PNG，R2 配了 `publicRead` 时上传 `screenshots/{deviceId}/{ts}.png` 并在 `result.output` 回 `screenshotUrl=`（Web 可直接展示）；无可用 R2 时降采样压缩为 ≤720px 的 JPEG 以 `screenshotBase64=` 经 MQTT 回传（受 EMQX Serverless 单消息 1MB 上限约束），再失败才报 `SCREENSHOT_TOO_LARGE`。
- **[`RootExecutor`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/executor/RootExecutor.kt)（最高特权通道）**：
  - 针对 Magisk / KernelSU / APatch 环境，直接以 `su` 权限无限制执行底层 Shell 命令。
- **[`AccessibilityExecutor`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/executor/AccessibilityExecutor.kt)（无障碍模拟通道）**：
  - 注册 [`ControlledAccessibilityService`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/accessibility/ControlledAccessibilityService.kt)；
  - 实时捕获窗口焦点事件，并在无 Root/Shizuku 环境下降级实现手势模拟（点击、双击、滑动）。
- **[`DeviceAdminExecutor`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/executor/DeviceAdminExecutor.kt)（设备管理器通道）**：
  - 基于 [`ControlledDeviceAdminReceiver`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/admin/ControlledDeviceAdminReceiver.kt) 实现即时息屏、设备自锁。
- **[`NormalExecutor`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/executor/NormalExecutor.kt)（应用层常规通道）**：
  - 普通 Android API 调用，处理标准 Intent 广播与常规应用启动。

---

### 2. 精细化应用防沉迷与使用时长管控（[`AppTimeController`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/apptime/AppTimeController.kt)）
- **单日累计使用时长限制（`app_time_limit`）**：
  - 后台周期性（每分钟）检测当前前台运行的应用包名，累加当日活跃时间；
  - 一旦应用达到配额阈值（例如单日 60 分钟），系统自动调用 `pm suspend` / `am suspend` 将该应用包置为挂起状态；
  - 挂起状态下应用图标变灰，用户点击启动将弹出系统级提示；次日 00:00 自动执行解挂恢复。
- **时间窗口禁闭策略（`app_time_window`）**：
  - 针对指定应用设置生效时间区间（原生支持跨零点时间窗，如 `22:30-07:00`）；
  - 处于禁闭时间窗内时应用自动保持挂起，到达解封时间点由守护协程自动执行恢复。

---

### 3. 交互式通知下发与签收闭环（[`ReminderNotificationCenter`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/notification/ReminderNotificationCenter.kt)）
- 接收来自 MQTT `reminder/{deviceId}` 主题的通知载荷；
- 创建高优先级（`IMPORTANCE_HIGH`）悬浮常驻通知，可配置标题、正文及最多 2 个操作按钮（如“确认完成”、“稍后提醒”）；
- 捕获用户在通知栏上的按钮点击事件，生成包含操作类型、时间戳与唯一 `ack_id` 的 `REMINDER_RESULT` 载荷；
- 经由 MQTT 回传至云端归档至 `task_ack` 表，形成端到端双向闭环。

---

### 4. 全维度系统遥测引擎（[`TelemetryEngine`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/telemetry/TelemetryEngine.kt)）
- **实时状态心跳**：电量百分比、充电状态、Wi-Fi / 移动蜂窝网络类型、信号强度（RSSI）、屏幕亮灭及前台应用包名。
- **GPS 定位追踪与地理围栏（[`LocationReporter`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/telemetry/LocationReporter.kt)）**：支持 FusedLocation / GPS 定位坐标回传，包含经纬度、速度与定位精度；内置地理围栏判断机制，一旦触发围栏越界（`FENCE_ENTER` / `FENCE_EXIT`）自动将上报策略提权至 QoS 1 保证触达。
- **应用使用时长统计（[`UsageReporter`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/telemetry/UsageReporter.kt)）**：调用 `UsageStatsManager` 获取前一天的详细应用使用流水，自动提取应用名称并上报。
- **离屏 MQTT 保活**：[`MqttManager`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/net/MqttManager.kt) 在息屏瞬间临时持有 `PARTIAL_WAKE_LOCK`（默认 15s / 连接 30s）完成消息收发，避免息屏后 CPU 休眠导致指令与遥测延迟。

---

### 5. 双通道容灾 OTA 自动升级（[`UpdateRunner`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/update/UpdateRunner.kt)）
- **四源并发竞速 + 中断换源**：调用 [`GitHubFastDownloader`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/update/GitHubFastDownloader.kt) 同时 HEAD 探测 4 个源（自有后端中转 `{server}/update/apk?url=` > 直连优选 IP > ghfast.top / gh-proxy.com / ghproxy.net），谁先返回 200 + `Content-Length` 谁排首位；赢家传输中断（EOF / StreamReset，大文件被中间链路掐断）时自动清理残片换下一个源续试，而非一次定生死。全部源 30s 无响应才判失败。
  - 直连走 GitHub520 优选 IP 快照（自定义 `okhttp3.Dns`，24h 刷新，SharedPreferences 缓存），TLS/SNI 与证书校验仍按原域名进行。
  - 加速链路只对 GitHub 真身生效：按 **host 白名单** 精确判断（`github.com` / `*.githubusercontent.com`），不做子串匹配——后端 `/update/check` 会把直链改写成中转链接，其 query 里含 `github.com` 字样，子串匹配会把它误送进加速器再套公共代理前缀导致 403。
- **三步会话流静默安装**：[`ShizukuExecutor.installApkStreamed`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/executor/ShizukuExecutor.kt) 走 `pm install-create -r` → `pm install-write -S <size> <id> base.apk -` → `pm install-commit <id>`，由 App 进程读自己的私有缓存 APK 经 stdin 喂给 Shizuku 侧 `pm`，绕开文件权限（旧实现 `pm install -r <path>` 必然 `Permission denied`：APK 在 0700 私有目录，Shizuku 进程是 shell uid）。
  - 写 stdin / 读 stdout / 读 stderr **三个协程并发**推进，写完立即 `close` 发 EOF（`pm` 收到 EOF 才提交安装），不等读完；顺序写完再读会因管道缓冲（~64KB）填满而死锁，表现为 `exit=1` 且无任何输出。
- **完整性校验**：下载后 sha256 比对 `sha256:` 前缀清单，差分包应用失败自动回退全量包（bsdiff 引擎未集成，`isBsdiffSupported()` 恒 false 直接短路全量）。
- **三触发源**：后端 `update_available` push（`CommandHandler` → [`UpdateRunner.trigger`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/update/UpdateRunner.kt)）、App 内「检查更新」按钮（`trigger("manual")`）、6 小时周期巡检（启动后先延迟 30 分钟）。`runOnce` 由 `Mutex` 串行化。
- **无 Shizuku 回退**：`FileProvider`（`${applicationId}.fileprovider` + `cache/updates` 路径）共享 APK 拉起系统安装器，需一次「安装未知应用」人工授权，非静默。

---

### 6. 小米 MIUI / HyperOS 深度保活适配（[`MiuiAdapter`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/oem/MiuiAdapter.kt)）
- 自动适配 MIUI / HyperOS 后台神隐模式、省电策略白名单。
- 引导开启“自启动”、“后台弹出界面”及无障碍服务防系统杀后台机制。
- 采用前台服务（[`ControlledService`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/service/ControlledService.kt)）+ `WorkManager`（[`HeartbeatGuardWorker`](file:///D:/手机控制/adbcontrol-controlled/controlled/src/main/kotlin/com/adbcontrol/controlled/service/HeartbeatGuardWorker.kt)）+ `BootReceiver` 双重看门狗守护。

---

## 🗂️ 目录与包结构

```text
adbcontrol-controlled/
├── controlled/src/main/kotlin/com/adbcontrol/controlled/
│   ├── accessibility/              # 无障碍服务及手势模拟
│   ├── admin/                      # 设备管理器组件
│   ├── apptime/                    # 应用时长限制与时间窗控制
│   ├── config/                     # 设备唯一标识、配置加密存储 (EncryptedFile)
│   ├── di/                         # Hilt 依赖注入模块 (AppModule)
│   ├── executor/                   # 5 层自适应命令执行器
│   ├── net/                        # Paho MQTT 客户端管理与消息编解码
│   ├── notification/               # 交互式通知展示与签收回报
│   ├── oem/                        # 小米 / MIUI / HyperOS 专用适配
│   ├── service/                    # 前台服务、开机自启与看门狗
│   ├── storage/                    # Cloudflare R2 对象直传客户端
│   ├── telemetry/                  # 电量、网络、GPS、应用行为与用量采集
│   ├── ui/                         # Jetpack Compose 界面与 ViewModel
│   └── update/                     # OTA 升级运行器与镜像下载器
├── shared/                         # 跨端共享协议与模型 (引用自 shared 工程)
├── (keystore 已移出仓库,v2.0 起由环境变量 / CI Secret 提供签名)
└── build.gradle.kts                # Android 模块构建配置
```

---

## 🔨 编译与安装指南

### 1. 配置 Android SDK 路径
在 `adbcontrol-controlled/local.properties` 文件中指定 Android SDK 目录：
```properties
sdk.dir=C\:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk
```

### 2. 编译 APK
```bash
cd adbcontrol-controlled

# 编译 Debug 版
./gradlew :controlled:assembleDebug

# 产物输出于: controlled/build/outputs/apk/debug/controlled-debug.apk

# 编译 Release 版(R8 + 资源压缩,~11MB)——与 CI 发布产物一致
./gradlew :controlled:assembleRelease

# 显式指定版本号(CI 用 git rev-list 提交计数作 versionCode 保证单调递增)
./gradlew :controlled:assembleRelease -PversionCode=42 -PversionName=1.4.2
```

> **签名说明(v2.0 起)**：签名文件不再入库。Gradle 从环境变量 `SIGNING_KEYSTORE_PATH` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` 读取签名;未配置时 debug(以及本地 release)回退 AGP 默认调试签名(**不能**覆盖安装现网设备)。CI(`ota.yml`)从 Secret `SIGNING_KEYSTORE_BASE64` 解码 keystore,缺失即失败。为保证现网设备 OTA 可覆盖安装,Secret 必须是**原** keystore(未轮换);旧 keystore 仍保留在 git 历史中,若将来轮换密钥,所有已安装设备需一次性卸载重装。R8 + 资源压缩保持开启(~11MB)。

### 3. 安装与启动
```bash
# 安装到已连接设备 (-r 允许覆盖更新)
adb install -r controlled/build/outputs/apk/debug/controlled-debug.apk

# 启动主界面
adb shell am start -n com.adbcontrol.controlled/.ui.MainActivity
```

---

## 🚀 OTA 发布流水线（GitHub Actions）

工作流：[`ota.yml`](file:///D:/手机控制/adbcontrol-controlled/.github/workflows/ota.yml)

| 触发            | Release tag | 版本号            | 说明                                     |
| ------------- | ----------- | --------------- | -------------------------------------- |
| 推送 `v*`      | `v1.0.1`    | tag 名（去 `v`）     | 正式命名发布，生成 release notes          |
| 推送 `main`    | `nightly`   | `nightly-{短sha}` | 滚动 nightly，**先删旧 nightly 再传新包**   |

流程：解码签名 Secret → `assembleRelease`(R8，固定签名) → 计算 sha256 + 体积 → 上传 GitHub Release → `POST {后端}/api/updates/publish` 注册版本清单并广播 `update_available`（后端非 200 即失败退出）。

所需仓库 Secrets：`ADB_BACKEND_URL`（后端地址）、`ADB_PM_TOKEN`（与后端 `ADB_PM_TOKEN` 环境变量一致）。

---

## 📲 真机初始化与授权流程

为使受控端发挥完整管控能力，建议按照以下步骤完成首次配置：

1. **配对绑定**：
   - 打开 Web 管理控制台 ➔ **令牌管理** ➔ 新增配对令牌；
   - 打开手机端 AdbControl App，使用内置扫码器扫描屏幕二维码，或手动输入 Token 和后端 API 地址完成绑定；
   - 配对成功后，凭证将通过 AES-GCM 加密安全写入设备受保护存储中。
2. **启用 Shizuku 特权授权（关键）**：
   - 手机安装 [Shizuku](https://shizuku.rikka.app/) 官方应用；
   - 在手机开发者选项中开启“无线调试”或通过 PC 执行 `adb shell sh .../start.sh` 启动 Shizuku；
   - 在 Shizuku 中为 `AdbControl Controlled` 勾选授权。受控端 UI 将显示“Shizuku 已连接”。
3. **授予辅助权限**：
   - **电池优化白名单**：允许后台无限制高耗电运行，避免被系统清理；
   - **使用情况访问权限**：用于统计各应用实际活跃使用时长；
   - **无障碍权限（可选）**：用于前台窗口感知与手势辅助。必须走系统「设置 → 无障碍 → 已下载服务」标准授权；应用内「一键开启」仅在 Shizuku 可用时通过直写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 生效，且会同步关闭触摸探索。App 内**不再提供无障碍快捷方式开关**（早期实现会在开启时顺带打开触摸探索，导致"屏幕失控"）。
   - **设备管理器（可选）**：用于远程锁屏控制。

> **通知监控能力已下线**：`ControlledNotificationListenerService`（通知内容监听 + ADB 冗余）及其权限项、遥测字段已整体移除，不再需要授予「通知访问」权限。现有的任务栏通知是**下行下发 + 点击签收**（`ReminderNotificationCenter`），不需要通知访问权限。

---

## 🔍 常用调试指令

```bash
# 查看被控端实时日志 (过滤关键 Tag)
adb logcat -s "ControlledApp" "CommandDispatcher" "MqttManager" "AppTimeController" "UpdateRunner" "GitHubFastDl" "SelfHostedUpdate"

# OTA 检查/下载/安装全过程诊断(check 请求、http 状态、竞速赢家、换源、sha256)
adb logcat -s "UpdateRunner" "SelfHostedUpdate" "GitHubFastDl"

# 读取落盘的 OTA 诊断日志(check 阶段完整 URL + 响应体片段)
adb shell run-as com.adbcontrol.controlled cat cache/ota_diag.txt

# 查看受控端前台服务状态
adb shell dumpsys activity services com.adbcontrol.controlled

# 检查当前受控端已安装包
adb shell pm list packages | grep adbcontrol

# 确认当前安装包的签名来源(OTA 覆盖失败时先查这一项)
adb shell dumpsys package com.adbcontrol.controlled | grep -i "signing\|version"

# 手动清除应用数据 (重新进入初始配对状态)
adb shell pm clear com.adbcontrol.controlled
```

## 🆕 v2.0 更新摘要

- **协议版本**:所有 MQTT 信封 / 协议模型带 `protocolVersion`(发送 2;接收缺失视为 1 并容忍)。
- **OTA 完整性**:下载后校验 SHA-256,不一致删除并上报 `UPDATE_HASH_MISMATCH`,绝不安装。
- **申请延时**:拦截页「申请再用 N 分钟」→ `request/{deviceId}` 发布 `EXTENSION_REQUEST`;处理 `APP_TIME_EXTEND` / `APP_TIME_EXTEND_DENIED`。
- **准实时用量**:每 15 分钟上报当日累计(`partial: true`)。
- **地理围栏**:处理 `GEOFENCE_SET`,持久化并应用。
- **审计**:命令回执带执行通道 `channel`。
- **新界面**:权限自检向导、受限应用拦截页、Material 3 动态取色首页、「本设备受管理」透明度页与常驻通知。
- **省电**:电量 <20% 或静止时降低定位 / 心跳频率。

