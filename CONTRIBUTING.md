# Contributing

Thanks for considering a contribution.

## Development

1. Install Android SDK 36.
2. Build with `.\tools\build.ps1`.
3. Test on a rooted Android device before changing guard behavior.

## Rules

- Keep the app root-only. Do not add Device Admin APIs.
- Keep user-facing strings in Android resources.
- Do not commit APKs, screenshots, local paths, keystores, or build outputs.
- Document behavior changes in `README.md` and `README.zh-CN.md`.

## Compatibility Notes

Root behavior varies across devices. When reporting compatibility issues, include Android version, device model, root manager, and whether `su`, `settings`, and `input` work from the app.
