#!/usr/bin/env bash
# Only for the disposable API-26 emulator created by the device CI job.
set -euo pipefail

device_serial=emulator-5554
device_evidence=app/build/outputs/device-diagnostics
qemu=$(timeout 20s adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')
if [[ "$qemu" != 1 ]]; then
  echo 'Refusing synthetic PIN/network setup outside the disposable emulator.' >&2
  exit 1
fi
mkdir -p "$device_evidence"

collect_failure() {
  local result=$?
  trap - EXIT
  if [[ "$result" -ne 0 ]]; then
    # Read only synthetic emulator diagnostics, retaining the original failure.
    set +e
    timeout 15s adb -s "$device_serial" shell dumpsys meminfo > "$device_evidence/api26-memory.txt" 2>&1
    timeout 15s adb -s "$device_serial" shell cat /proc/meminfo > "$device_evidence/api26-proc-memory.txt" 2>&1
    timeout 15s adb -s "$device_serial" shell dumpsys connectivity > "$device_evidence/api26-connectivity.txt" 2>&1
    timeout 15s adb -s "$device_serial" logcat -d -v threadtime -t 500 > "$device_evidence/api26-setup-logcat.txt" 2>&1
    timeout 15s adb -s "$device_serial" shell dumpsys window > "$device_evidence/api26-window.txt" 2>&1
    timeout 15s adb -s "$device_serial" exec-out screencap -p > "$device_evidence/api26-setup.png" 2>/dev/null
  fi
  exit "$result"
}
trap collect_failure EXIT
timeout 20s adb -s "$device_serial" shell getprop ro.build.version.sdk > "$device_evidence/api26-platform.txt"
[[ "$(tr -d '\r\n' < "$device_evidence/api26-platform.txt")" == 26 ]]
timeout 20s adb -s "$device_serial" shell locksettings set-pin 2468
timeout 20s adb -s "$device_serial" shell settings put system screen_off_timeout 1800000
timeout 20s adb -s "$device_serial" shell svc power stayon true
timeout 20s adb -s "$device_serial" shell input keyevent KEYCODE_WAKEUP
timeout 20s adb -s "$device_serial" shell wm dismiss-keyguard
timeout 20s adb -s "$device_serial" shell settings put global airplane_mode_on 1
# API 26 denies AIRPLANE_MODE broadcasts from shell. Do not suppress that error
# or grant root: disable both transports, then assert actual network absence
# independently inside every selected instrumentation scenario.
timeout 20s adb -s "$device_serial" shell svc wifi disable
timeout 20s adb -s "$device_serial" shell svc data disable
