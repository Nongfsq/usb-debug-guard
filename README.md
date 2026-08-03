# USB Debug Guard

USB Debug Guard is a root-only Android tool for long USB debugging sessions. It protects the display while keeping ADB available, then restores the previous display settings when protection stops.

[简体中文](README.zh-CN.md)

## When to Use It

- Long Android development or automated testing sessions over USB.
- Devices that must remain reachable through ADB with the screen off.
- Devices that cannot keep ADB stable with the screen off and need a dim-awake fallback.

## Features

- Detects USB data connections and ADB state through Android system APIs without routine Root polling.
- Turns the screen off while keeping ADB reachable, or keeps it awake at minimum brightness.
- Saves and restores the previous display settings.
- Can lock the device after USB disconnects.
- Runs only after the user enables its foreground service.
- Supports English and Simplified Chinese.
- Checks GitHub Releases only when the user taps **Check updates**; it does not perform background update checks.
- Stops automatic Root work after a denial, timeout, exception, or process cleanup failure, preventing an endless `su` retry loop.

## Requirements

- Android 8.0 or newer.
- A working Root manager such as Magisk or KernelSU.
- A Root environment that can run Android `settings` and `input` commands.
- A USB data connection. A charge-only cable or charger does not start protection.

Root behavior varies between devices, OEM firmware, and Root managers. Test the app on a non-critical device before relying on it for unattended sessions.

## Install

Download the APK from [GitHub Releases](https://github.com/Nongfsq/usb-debug-guard/releases/latest) and install it on the Android device.

### Upgrading from v0.1.2

v0.1.3 uses a new signing certificate, so Android cannot install it directly over v0.1.2:

1. Disable the guard and confirm that the display settings are restored.
2. Uninstall v0.1.2.
3. Install v0.1.3 or newer.
4. Configure the app again and grant Root when requested.

## Use

1. Open the app and allow notifications so protection and failure states remain visible.
2. Select a protection mode:
   - **Screen off while guarded**: recommended; turns the screen off while ADB remains reachable.
   - **Dim but keep awake**: fallback for devices that lose ADB when the display sleeps.
3. Enable **Require ADB debugging** if protection should start only while ADB is enabled.
4. Optionally enable locking after USB disconnect or keyguard dismissal in dim mode.
5. Turn on **Enable guard** and grant Root.
6. Connect a USB data cable and enable USB debugging. The status changes to **Protected** when protection is active.
7. Turn off the guard before uninstalling the app or changing the Root environment.

## Root Failure Safety

If a Root request is denied, times out, throws an exception, or leaves a process running, USB Debug Guard opens its safety circuit and stops automatic Root requests. Service restarts and device reboots do not clear this state.

Check the diagnostics shown in the app. Use **Retry Root** only after fixing the Root environment. If display restoration fails, keep the app running and resolve the Root problem before disconnecting or uninstalling it.

## Build

Install Android SDK 36, then run:

```sh
./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Run the scripted regression suite with:

```sh
./tools/test-root-loop-fix.sh
```

## Privacy and Security

The app has no analytics and does not upload logs. Network access occurs only after a manual update check and is limited to the official GitHub Releases API and release page.

- [Privacy policy](PRIVACY.md)
- [Security policy](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [Release process](docs/RELEASE.md)

## License

[Apache License 2.0](LICENSE)
