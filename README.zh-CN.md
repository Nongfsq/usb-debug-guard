# USB 调试守护

USB 调试守护是一个 root-only Android 工具，用于长时间 USB 调试。它在保持 ADB 可访问的同时，降低屏幕损耗、发热和耗电。

## 功能

- 仅在用户启用守护后运行前台服务。
- 检测 USB 供电状态和 ADB 调试状态。
- 使用 root (`su`) 保存和恢复显示设置。
- 默认模式会在守护时灭屏，同时保持 USB 调试可访问。
- 备用的低亮常亮模式会把亮度降到最低并保持屏幕唤醒。
- 停止守护或 USB 断开后恢复原显示状态。

## 要求

- 已 root 的 Android 设备。
- 可用的 `su` 实现。
- root 环境中可用的 Android 命令：`settings`、`input`，以及可选的 `wm`。
- Android 8.0 或更新版本。

Root 不代表所有设备都 100% 兼容。OEM 系统、root 管理器、SELinux 策略和 USB 供电状态上报都可能影响行为。

## 构建

安装 Android SDK 36，然后运行：

```powershell
.\tools\build.ps1
```

调试 APK 会生成在：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 安装

```powershell
.\tools\install.ps1
```

或者手动安装：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## ADB 控制

前台服务暴露仅 shell 可调用的 action，并通过 `android.permission.DUMP` 限制普通第三方应用调用：

```powershell
adb shell am start-foreground-service -a io.github.nongfsq.usbdebugguard.action.START -n io.github.nongfsq.usbdebugguard/.GuardService
adb shell am startservice -a io.github.nongfsq.usbdebugguard.action.STOP -n io.github.nongfsq.usbdebugguard/.GuardService
adb shell am startservice -a io.github.nongfsq.usbdebugguard.action.REFRESH -n io.github.nongfsq.usbdebugguard/.GuardService
```

## 隐私

USB 调试守护不收集分析数据、不联网、不上传日志，也不保存个人信息。详见 [PRIVACY.md](PRIVACY.md)。

## 许可证

Apache License 2.0。详见 [LICENSE](LICENSE)。
