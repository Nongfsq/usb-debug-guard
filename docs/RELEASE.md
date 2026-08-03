# Release process

## Signing continuity

Android updates must use the same signing key as v0.1.2.

- Certificate subject: `CN=USB Debug Guard, O=Nongfsq, C=CA`
- Certificate SHA-256: `307ca12a3c5152e2150acc66fddd04dade7ec2480a5e43680b350a7fbadecd1a`
- RSA key size: 4096 bits

Never commit the keystore or its passwords. Before publishing, verify the candidate APK with `apksigner verify --verbose --print-certs` and compare the certificate fingerprint above. If the original private key is unavailable, stop the release: a newly signed APK cannot update existing installations.

## Checklist

1. Confirm versionCode/versionName and update `CHANGELOG.md` plus bilingual release notes.
2. Run `tools/test-root-loop-fix.sh`.
3. Run `./gradlew connectedDebugAndroidTest` on an explicitly selected emulator.
4. Run `./gradlew lint assembleDebug assembleRelease`.
5. Sign the release APK with the existing release key outside the repository.
6. Verify APK signature, manifest permissions, version metadata, and SHA-256.
7. Tag the exact commit and publish the signed APK, `SHA256SUMS`, release notes, and public brand assets.
8. Confirm the GitHub `releases/latest` endpoint returns the new tag so the in-app update checker can discover it.
