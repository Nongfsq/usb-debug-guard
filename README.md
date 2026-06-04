# USB Debug Guard

USB Debug Guard is a root-only Android utility for long USB debugging sessions. It keeps ADB reachable while reducing screen wear, heat, and battery drain.

[简体中文说明](README.zh-CN.md)

## What It Does

- Runs a foreground service only after the user enables the guard.
- Detects USB power and ADB debugging state.
- Uses root (`su`) to save and restore display settings.
- Default mode turns the screen off while keeping USB debugging reachable.
- Optional dim-awake fallback keeps the display awake at minimum brightness.
- Restores the previous display state when protection stops or USB disconnects.

## Requirements

- A rooted Android device.
- A working `su` implementation.
- Android commands available to root: `settings`, `input`, and optionally `wm`.
- Android 8.0 or newer.

Root does not guarantee every device behaves the same way. OEM firmware, root managers, SELinux policy, and USB power reporting can affect compatibility.

## Build

Install Android SDK 36, then run:

```powershell
.\tools\build.ps1
```

The debug APK is created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Install

```powershell
.\tools\install.ps1
```

Or install manually:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## ADB Control

The foreground service exposes shell-only actions guarded by `android.permission.DUMP`:

```powershell
adb shell am start-foreground-service -a io.github.nongfsq.usbdebugguard.action.START -n io.github.nongfsq.usbdebugguard/.GuardService
adb shell am startservice -a io.github.nongfsq.usbdebugguard.action.STOP -n io.github.nongfsq.usbdebugguard/.GuardService
adb shell am startservice -a io.github.nongfsq.usbdebugguard.action.REFRESH -n io.github.nongfsq.usbdebugguard/.GuardService
```

## Privacy

USB Debug Guard does not collect analytics, does not connect to the network, does not upload logs, and does not store personal data. See [PRIVACY.md](PRIVACY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
