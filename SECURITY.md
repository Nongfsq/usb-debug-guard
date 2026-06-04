# Security Policy

## Supported Versions

Only the current main branch is supported before the first stable release.

## Reporting a Vulnerability

Please open a private security advisory on GitHub if the repository supports it. If not, open an issue with reproduction steps while avoiding sensitive device data.

## Root Scope

USB Debug Guard requires root to change display settings and send key events. It does not use Device Admin APIs and does not request `BIND_DEVICE_ADMIN`.

The service exposes ADB control actions, but the service is protected by `android.permission.DUMP` so normal third-party apps cannot call it.
