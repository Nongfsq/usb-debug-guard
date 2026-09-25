# Release process

## Signing certificates

The original v0.1.2 private key was not present after the development environment moved from Windows to macOS. v0.1.3 therefore starts a documented signing-key generation.

| Releases | Certificate SHA-256 | Status |
| --- | --- | --- |
| v0.1.0–v0.1.2 | `307ca12a3c5152e2150acc66fddd04dade7ec2480a5e43680b350a7fbadecd1a` | Original private key unavailable |
| v0.1.3 onward | `afcee14b04b1cb3e32b2f003c12da1b64e0e117d05bc389fcee226c5a9e17e6e` | Active release key |

Both certificates use subject `CN=USB Debug Guard, O=Nongfsq, C=CA` and 4096-bit RSA keys. The local v0.1.3+ keystore is stored under the ignored `.keystore/` directory with mode `0600`; its password is stored in macOS Keychain service `usb-debug-guard-release-v2`. Never commit either secret.

### v0.1.2 migration

Android will reject v0.1.3 as an in-place update over v0.1.2 because their signing certificates differ. Existing users must:

1. Disable USB Debug Guard and confirm display settings have been restored.
2. Uninstall v0.1.2.
3. Install v0.1.3.
4. Reconfigure preferences and grant Root again.

Uninstalling clears the app's local preferences. The in-app update page and v0.1.3 release notes must retain this notice.

## Checklist

1. Confirm versionCode/versionName and update `CHANGELOG.md` plus bilingual release notes.
2. Run `tools/test-root-loop-fix.sh`.
3. Run `./gradlew connectedDebugAndroidTest` on an explicitly selected emulator.
4. Run `./gradlew lint assembleDebug assembleRelease`.
5. Sign the release APK with the active v0.1.3+ key outside the repository.
6. Verify APK signature against the active fingerprint, manifest permissions, version metadata, zip alignment, and SHA-256.
7. Tag the exact commit and publish the signed APK, the signed F-Droid APK (see below), `SHA256SUMS`, release notes, and public brand assets.
8. Confirm the GitHub `releases/latest` endpoint returns the new tag so the in-app update checker can discover it.

## F-Droid reproducible build

F-Droid builds each tag with `-PupdateCheck=false`, compares the result with `usb-debug-guard-v<version>-fdroid.apk` from the GitHub release, and publishes that file with its original signature only if everything except the signature is identical. The recipe lives in fdroiddata as `metadata/io.github.nongfsq.usbdebugguard.yml`. Its `binary` and `AllowedAPKSigningKeys` fields point at this file and at the active certificate.

To produce the file:

1. Build from a fresh `git clone` checked out at the tag, not from a `git worktree`. AGP embeds the Git revision in `META-INF/version-control-info.textproto` and cannot read it from a worktree.
2. Run `./gradlew assembleRelease -PupdateCheck=false`.
3. Sign `app-release-unsigned.apk` directly. Do not run `zipalign` first, and pass `--v1-signing-enabled false --alignment-preserved true` to `apksigner sign`. Any change to the ZIP layout breaks F-Droid's signature copy.
4. Name the result `usb-debug-guard-v<version>-fdroid.apk` and add it to `SHA256SUMS`.

F-Droid's auto-update creates new builds from new tags. Each release also needs `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.

After every release, back up the active keystore to encrypted offline storage; Keychain alone is not a keystore backup.
