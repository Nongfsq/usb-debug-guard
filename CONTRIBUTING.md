# Contributing

Thanks for considering a contribution.

## Development

1. Install Android SDK 36.
2. Run `tools/test-root-loop-fix.sh` and `./gradlew lint assembleDebug assembleRelease`.
3. Use `USBGuard_API36_ATD` or another disposable emulator for Android integration tests.
4. Test Root-manager-specific behavior only on a disposable emulator or dedicated clean test device.

## Rules

- Keep the app root-only. Do not add Device Admin APIs.
- Keep user-facing strings in Android resources.
- Do not commit APKs, screenshots, local paths, keystores, or build outputs.
- Document behavior changes in `README.md` and `README.zh-CN.md`.
- Keep update checks user initiated; do not add background polling, analytics, or silent APK installation.

## Compatibility Notes

Root behavior varies across devices. When reporting compatibility issues, include Android version, device model, root manager, and whether `su`, `settings`, and `input` work from the app.
