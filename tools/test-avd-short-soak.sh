#!/bin/sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: $0 SERIAL [OUTPUT_DIR]" >&2
    exit 64
fi

SERIAL=$1
OUTPUT_DIR=${2:-outputs/avd-short-soak}
ADB=${ADB:-adb}
PACKAGE=io.github.nongfsq.usbdebugguard
SERVICE="$PACKAGE/.GuardService"
APK=app/build/outputs/apk/debug/app-debug.apk
BURST_SECONDS=${BURST_SECONDS:-360}
BURST_INTERVAL_SECONDS=${BURST_INTERVAL_SECONDS:-5}
QUIET_INTERVAL_SECONDS=${QUIET_INTERVAL_SECONDS:-1200}

case "$SERIAL" in
    emulator-*) ;;
    *)
        echo "FAIL: SERIAL must identify an Android Emulator" >&2
        exit 65
        ;;
esac

if "$ADB" devices | awk 'NR > 1 && $1 != "" { print $1 }' | grep -qv '^emulator-'; then
    echo "FAIL: a physical ADB device is connected" >&2
    exit 66
fi

if [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null)" != "device" ]; then
    echo "FAIL: emulator is not connected: $SERIAL" >&2
    exit 67
fi

if [ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]; then
    echo "FAIL: target does not report ro.kernel.qemu=1" >&2
    exit 68
fi

# USB_STATE is a protected system broadcast on current Android releases. The
# AOSP ATD image is userdebug, so use emulator-only adb root to inject it. This
# does not grant the application a working Magisk/KernelSU-style `su -c` path.
"$ADB" -s "$SERIAL" root >/dev/null
for _ in $(seq 1 30); do
    if [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" = "device" ]; then
        break
    fi
    sleep 1
done
if ! "$ADB" -s "$SERIAL" shell id | grep -q 'uid=0(root)'; then
    echo "FAIL: AOSP emulator did not enable adb root for USB_STATE injection" >&2
    exit 70
fi

mkdir -p "$OUTPUT_DIR"

adb_shell() {
    "$ADB" -s "$SERIAL" shell "$@"
}

root_count() {
    "$ADB" -s "$SERIAL" logcat -d -s UsbDebugGuardRoot:I '*:S' 2>/dev/null |
        grep -c 'requestId=' || true
}

snapshot() {
    name=$1
    {
        date -u '+captured_utc=%Y-%m-%dT%H:%M:%SZ'
        printf 'root_diagnostic_count=%s\n' "$(root_count)"
        printf 'app_pid=%s\n' "$(adb_shell pidof "$PACKAGE" 2>/dev/null || true)"
        adb_shell dumpsys deviceidle 2>/dev/null | grep -E 'mState=|mLightState=|mForceIdle=' || true
        adb_shell dumpsys activity services "$PACKAGE" 2>/dev/null || true
        adb_shell dumpsys power 2>/dev/null | grep -i -C 2 "$PACKAGE" || true
        adb_shell run-as "$PACKAGE" cat shared_prefs/guard.xml 2>/dev/null || true
    } > "$OUTPUT_DIR/$name.txt"
}

trigger_events() {
    adb_shell am start-foreground-service -n "$SERVICE" -a "$PACKAGE.action.START" >/dev/null
    adb_shell am start-foreground-service -n "$SERVICE" -a "$PACKAGE.action.REFRESH" >/dev/null
    adb_shell am start-foreground-service -n "$SERVICE" -a "$PACKAGE.action.LOCK_NOW" >/dev/null
    adb_shell am broadcast \
        -a android.hardware.usb.action.USB_STATE \
        --ez connected true --ez adb true --receiver-registered-only >/dev/null
}

assert_count_unchanged() {
    stage=$1
    actual=$(root_count)
    if [ "$actual" -ne "$BASELINE_ROOT_COUNT" ]; then
        echo "FAIL: Root request count changed during $stage: baseline=$BASELINE_ROOT_COUNT actual=$actual" >&2
        snapshot "failure-$stage"
        exit 1
    fi
}

{
    date -u '+started_utc=%Y-%m-%dT%H:%M:%SZ'
    printf 'serial=%s\n' "$SERIAL"
    printf 'fingerprint=%s\n' "$(adb_shell getprop ro.build.fingerprint | tr -d '\r')"
    printf 'burst_seconds=%s\n' "$BURST_SECONDS"
    printf 'burst_interval_seconds=%s\n' "$BURST_INTERVAL_SECONDS"
    printf 'quiet_interval_seconds=%s\n' "$QUIET_INTERVAL_SECONDS"
} > "$OUTPUT_DIR/environment.txt"

if [ ! -f "$APK" ]; then
    echo "FAIL: debug APK is missing: $APK" >&2
    exit 69
fi

"$ADB" -s "$SERIAL" install -r -t "$APK" > "$OUTPUT_DIR/install.txt"
adb_shell pm clear "$PACKAGE" > "$OUTPUT_DIR/pm-clear.txt"
adb_shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" -s "$SERIAL" logcat -c

adb_shell am start-foreground-service -n "$SERVICE" -a "$PACKAGE.action.START" >/dev/null
adb_shell am start-foreground-service -n "$SERVICE" -a "$PACKAGE.action.LOCK_NOW" >/dev/null
sleep 3
BASELINE_ROOT_COUNT=$(root_count)
snapshot initial-circuit

if [ "$BASELINE_ROOT_COUNT" -ne 1 ]; then
    echo "FAIL: expected exactly one initial Root request, got $BASELINE_ROOT_COUNT" >&2
    exit 1
fi

BURST_STARTED=$(date +%s)
BURST_END=$((BURST_STARTED + BURST_SECONDS))
BURST_ITERATIONS=0
while [ "$(date +%s)" -lt "$BURST_END" ]; do
    trigger_events
    BURST_ITERATIONS=$((BURST_ITERATIONS + 1))
    sleep "$BURST_INTERVAL_SECONDS"
done
assert_count_unchanged concentrated-burst
snapshot after-concentrated-burst

adb_shell dumpsys deviceidle enable > "$OUTPUT_DIR/deviceidle-enable.txt"
adb_shell dumpsys deviceidle force-idle > "$OUTPUT_DIR/force-idle.txt"
if ! adb_shell dumpsys deviceidle | grep -q 'mState=IDLE'; then
    echo "FAIL: emulator did not enter deep idle" >&2
    exit 1
fi
snapshot idle-start
sleep "$QUIET_INTERVAL_SECONDS"
trigger_events
sleep 3
assert_count_unchanged first-quiet-wakeup
snapshot after-first-quiet-wakeup

sleep "$QUIET_INTERVAL_SECONDS"
trigger_events
sleep 3
assert_count_unchanged second-quiet-wakeup
snapshot after-second-quiet-wakeup

adb_shell dumpsys deviceidle unforce > "$OUTPUT_DIR/unforce-idle.txt" 2>&1 || true
adb_shell am start-foreground-service -n "$SERVICE" -a "$PACKAGE.action.STOP" >/dev/null || true
sleep 2

{
    date -u '+finished_utc=%Y-%m-%dT%H:%M:%SZ'
    printf 'result=PASS\n'
    printf 'baseline_root_diagnostic_count=%s\n' "$BASELINE_ROOT_COUNT"
    printf 'final_root_diagnostic_count=%s\n' "$(root_count)"
    printf 'burst_iterations=%s\n' "$BURST_ITERATIONS"
    printf 'burst_trigger_actions=%s\n' "$((BURST_ITERATIONS * 4))"
    printf 'quiet_wakeup_checks=2\n'
} | tee "$OUTPUT_DIR/result.txt"
