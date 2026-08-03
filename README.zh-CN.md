# USB 调试守护

USB 调试守护是一款面向长时间 USB 调试的 Android Root 工具。它会在保持 ADB 可连接的同时保护屏幕，并在停止保护后恢复原来的显示设置。

[English](README.md)

## 适用场景

- 长时间进行 Android 开发、调试或自动化测试。
- 需要关闭屏幕，但仍要通过 USB 保持 ADB 连接。
- 设备灭屏后 ADB 不稳定，需要使用低亮常亮作为备用方案。

## 功能

- 通过 Android 系统 API 检测 USB 数据连接和 ADB 状态，常态探测不调用 Root。
- 支持守护时灭屏，也支持最低亮度常亮模式。
- 保存并恢复原来的显示设置。
- 可在 USB 断开后锁定设备。
- 仅在用户启用守护后运行前台服务。
- 支持英文和简体中文。
- 仅在用户点击“检查更新”时查询 GitHub Releases，不在后台自动检查。
- Root 被拒绝、超时、异常或进程清理失败后停止自动 Root 操作，避免无限生成 `su` 进程。

## 使用要求

- Android 8.0 或更新版本。
- 可正常工作的 Root 管理器，例如 Magisk 或 KernelSU。
- Root 环境能够运行 Android 的 `settings` 和 `input` 命令。
- USB 数据连接。仅供电的充电器或充电线不会触发保护。

不同设备、厂商系统和 Root 管理器的行为可能不同。用于无人值守任务前，请先在非关键设备上验证。

## 安装

从 [GitHub Releases](https://github.com/Nongfsq/usb-debug-guard/releases/latest) 下载 APK，并安装到 Android 设备。

### 从 v0.1.2 升级

v0.1.3 使用了新的签名证书，Android 不能直接覆盖安装：

1. 关闭守护并确认显示设置已经恢复。
2. 卸载 v0.1.2。
3. 安装 v0.1.3 或更新版本。
4. 重新配置应用，并在需要时授予 Root。

## 使用方法

1. 打开应用并允许通知，以便持续看到保护状态和故障警告。
2. 选择保护模式：
   - **守护时灭屏**：推荐模式；关闭屏幕，同时保持 ADB 可连接。
   - **低亮常亮**：适合屏幕休眠后 ADB 会断开的设备。
3. 如需只在 ADB 开启时保护，请启用**要求 ADB 调试**。
4. 根据需要启用 USB 断开后锁屏，或低亮模式跳过锁屏界面。
5. 打开**启用守护**并授予 Root。
6. 连接 USB 数据线并开启 USB 调试。状态变为**已保护**后，保护已经生效。
7. 卸载应用或调整 Root 环境前，先关闭守护。

## Root 故障保护

如果 Root 请求被拒绝、超时、发生异常或遗留进程，USB 调试守护会打开安全熔断，并停止自动 Root 请求。服务重启和设备重启不会清除这个状态。

请先查看应用内诊断并修复 Root 环境，之后再主动点击**重试 Root**。如果显示设置恢复失败，请保持应用运行，并在断开 USB 或卸载应用前解决 Root 问题。

## 构建

安装 Android SDK 36，然后运行：

```sh
./gradlew assembleDebug
```

APK 会生成到 `app/build/outputs/apk/debug/app-debug.apk`。

运行脚本回归测试：

```sh
./tools/test-root-loop-fix.sh
```

## 隐私与安全

应用不包含数据分析，也不会上传日志。只有用户主动检查更新时才会访问官方 GitHub Releases API 和发布页面。

- [隐私说明](PRIVACY.md)
- [安全策略](SECURITY.md)
- [更新日志](CHANGELOG.md)
- [发布流程](docs/RELEASE.md)

## 许可证

[Apache License 2.0](LICENSE)
