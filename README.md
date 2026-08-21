# adbcontrol-controlled

AdbControlApp 的**被控端** Android App（Jetpack Compose + Hilt）。经多通道执行器（Shizuku / Root / Accessibility / 普通）执行主控端指令，并周期回传遥测数据（状态 / 位置 / 应用行为 / 健康）。

> 这是 **AdbControlApp**（基于 MQTT + Shizuku 的 Android 设备管理 Agent 平台）拆分出的 4 个仓库之一。完整架构设计见本仓库的 [DESIGN.md](DESIGN.md)。

## 相关仓库

| 仓库 | 说明 |
| --- | --- |
| [adbcontrol-backend](https://github.com/qimingnan17/adbcontrol-backend) | 后端服务 |
| [adbcontrol-controller](https://github.com/qimingnan17/adbcontrol-controller) | 主控端 Android App |
| [adbcontrol-controlled](https://github.com/qimingnan17/adbcontrol-controlled) | 被控端 Android App（本仓库） |
| [adbcontrol-web](https://github.com/qimingnan17/adbcontrol-web) | Web 管理端 |

## 模块

- `controlled/` — 被控端 App
- `shared/` — 跨端共享的协议与数据模型（`com.adbcontrol.shared`）

## 构建

```bash
./gradlew :controlled:assembleDebug
```

## 配置

在 `local.properties` 中配置 `sdk.dir`（Android SDK 路径）。复制 `secrets.properties.template` 为 `secrets.properties` 填入所需凭证，凭证文件不进入 git。
