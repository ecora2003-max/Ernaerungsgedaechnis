#!/usr/bin/env bash
# emulator-runner starts each script input line in a separate shell. Keep the
# exit status and diagnostic capture in this one process, including on failure.
set -uo pipefail

device_test_args=()
if [[ -n "${EG_ANDROID_TEST_CLASS:-}" ]]; then
  device_test_args+=("-Pandroid.testInstrumentationRunnerArguments.class=${EG_ANDROID_TEST_CLASS}")
fi
./gradlew --no-daemon --max-workers=2 :app:connectedDebugAndroidTest "${device_test_args[@]}"
device_test_result=$?
if [[ "$device_test_result" -ne 0 ]]; then
  mkdir -p app/build/outputs/device-diagnostics
  adb shell dumpsys window > app/build/outputs/device-diagnostics/window.txt
  adb exec-out screencap -p > app/build/outputs/device-diagnostics/screen.png
fi
exit "$device_test_result"
