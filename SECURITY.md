# Security Policy

## Supported Versions

Only the current main branch is supported before the first stable release.

## Reporting a Vulnerability

Please open a private security advisory on GitHub if the repository supports it. If not, open an issue with reproduction steps while avoiding sensitive device data.

## Root Scope

USB Debug Guard requires root to change display settings and send key events. It does not use Device Admin APIs and does not request `BIND_DEVICE_ADMIN`.

The service exposes ADB control actions, but the service is protected by `android.permission.DUMP` so normal third-party apps cannot call it.

## Updates

Update checks are user initiated and query only the official GitHub Releases API over HTTPS. The app compares the release tag locally and opens the repository's fixed official release URL in the user's browser. It does not download or install APKs silently and does not request package-install permissions.

Published APKs must use the active release signing certificate documented in `docs/RELEASE.md`. v0.1.3 starts a new signing generation because the v0.1.2 private key was unavailable; the release notes explicitly require uninstalling v0.1.2 before installing v0.1.3.
