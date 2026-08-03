#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d /opt/homebrew/share/android-commandlinetools ]; then
    export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

PROBE="app/src/main/kotlin/io/github/nongfsq/usbdebugguard/UsbAdbProbe.kt"
SERVICE="app/src/main/kotlin/io/github/nongfsq/usbdebugguard/GuardService.kt"
STATE_MACHINE="app/src/main/kotlin/io/github/nongfsq/usbdebugguard/GuardStateMachine.kt"
ROOT_EXECUTOR="app/src/main/kotlin/io/github/nongfsq/usbdebugguard/RootExecutor.kt"
MAIN_ACTIVITY="app/src/main/kotlin/io/github/nongfsq/usbdebugguard/MainActivity.kt"
INSTALL_SCRIPT="tools/install.ps1"

if grep -Eq 'RootExecutor|RootRuntime|RootShell|ProcessBuilder|(^|[^[:alnum:]_])su([^[:alnum:]_]|$)' "$PROBE"; then
    echo "FAIL: normal USB/ADB probe references root execution" >&2
    exit 1
fi

if grep -Eq 'POLL_INTERVAL|postDelayed' "$SERVICE"; then
    echo "FAIL: GuardService contains fixed-frequency polling" >&2
    exit 1
fi

if grep -Eq 'BatteryManager|BATTERY_PLUGGED_USB|ACTION_BATTERY_CHANGED' "$PROBE"; then
    echo "FAIL: USB probe still treats charging as a USB data session" >&2
    exit 1
fi

if grep -Eq 'while[[:space:]]*\(true\)|delay\(2_000' "$MAIN_ACTIVITY"; then
    echo "FAIL: MainActivity still contains fixed-frequency background polling" >&2
    exit 1
fi

if ! grep -q 'descendants' "$ROOT_EXECUTOR" || ! grep -q 'manualRetryConsumed' "$ROOT_EXECUTOR"; then
    echo "FAIL: process-tree cleanup or persistent retry budget is missing" >&2
    exit 1
fi

if grep -q 'joinToString("; ")' "$STATE_MACHINE" || ! grep -q 'UDG_SETTING_' app/src/main/kotlin/io/github/nongfsq/usbdebugguard/DisplaySettingsSnapshot.kt; then
    echo "FAIL: root commands are not fail-fast or snapshot output is not named" >&2
    exit 1
fi

if grep -Eq 'reset|manualRetry' app/src/main/kotlin/io/github/nongfsq/usbdebugguard/BootReceiver.kt; then
    echo "FAIL: BootReceiver can reset root safety state" >&2
    exit 1
fi

if ! grep -q 'dataExtractionRules="@xml/data_extraction_rules"' app/src/main/AndroidManifest.xml ||
   ! grep -q 'exclude domain="sharedpref"' app/src/main/res/xml/data_extraction_rules.xml; then
    echo "FAIL: sensitive SharedPreferences migration exclusion is missing" >&2
    exit 1
fi

if ! grep -Eq '^distributionSha256Sum=[0-9a-f]{64}$' gradle/wrapper/gradle-wrapper.properties; then
    echo "FAIL: Gradle distribution checksum is missing" >&2
    exit 1
fi

if ! grep -Eq 'versionCode[[:space:]]*=[[:space:]]*[4-9][0-9]*' app/build.gradle.kts; then
    echo "FAIL: versionCode was not advanced beyond the incident build" >&2
    exit 1
fi

if ! grep -Fq "[Parameter(Mandatory = \$true)]" "$INSTALL_SCRIPT" ||
   ! grep -Fq -- "-s \$Serial install" "$INSTALL_SCRIPT" ||
   ! grep -q 'ro.kernel.qemu' "$INSTALL_SCRIPT" ||
   ! grep -q 'APK installation failed' "$INSTALL_SCRIPT"; then
    echo "FAIL: emulator-only install and exit-code guards are missing" >&2
    exit 1
fi

if ! git check-ignore -q USB-Debug-Guard-Incident-2026-08-01/README.md; then
    echo "FAIL: local incident evidence is not ignored by Git" >&2
    exit 1
fi

tools/check-public-tree.sh

./gradlew --no-daemon testDebugUnitTest

echo "PASS: root-loop static guards and JVM regression tests"
