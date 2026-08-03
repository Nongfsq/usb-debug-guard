# Changelog

All notable user-visible changes are recorded here.

## [0.1.3] - 2026-08-03

### Added

- User-initiated update checks against the official GitHub Releases API.
- Direct link to the official release page when a newer version is available.
- Structured Root diagnostics, persistent circuit state, manual-retry budget, and process-tree tracking.
- JVM, Android instrumentation, and emulator short-soak regression coverage.

### Changed

- Replaced fixed three-second service polling with USB and ADB events.
- Normal USB/ADB state probes now use Android APIs and never invoke Root.
- Root execution is application-singleton and globally single-flight.
- Display-setting capture/apply/restore now uses a crash-consistent transaction and fail-fast commands.
- USB power alone is no longer treated as an active USB data session.
- Activity state refresh follows lifecycle and preference events instead of background polling.
- Version advanced to `versionCode 4` / `versionName 0.1.3`.

### Fixed

- Stops automatic Root work after denied, exception, timeout, hang, or failed process destruction.
- Preserves the safety circuit across `START_STICKY`, service reconstruction, and boot recovery.
- Terminates and verifies known Root descendants instead of checking only the direct process.
- Propagates stdout/stderr collection failures and prevents pipe deadlocks.
- Preserves the original display snapshot across partial protection failures and retries.
- Keeps the service and warning state visible until a failed restore completes successfully.

### Security

- Excludes Root safety state and display snapshots from backup/device migration.
- Pins the Gradle distribution SHA-256.
- Restricts installer tooling to explicitly selected Android emulators.
- Update checks are manual, HTTPS-only, bounded, and do not download or install APKs.

## [0.1.2] - 2026-06-04

- Added in-app language selection and Android 13+ locale integration.
- Fixed adaptive icon layering and safe-zone geometry.

## [0.1.1] - 2026-06-04

- Refreshed logo and release assets.

## [0.1.0] - 2026-06-04

- Initial open-source release.
