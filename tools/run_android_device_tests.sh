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
  adb shell dumpsys connectivity > app/build/outputs/device-diagnostics/connectivity.txt
  adb exec-out screencap -p > app/build/outputs/device-diagnostics/screen.png
  # Capture external system surfaces at their failure boundary; later tests
  # can replace them before the final workflow screenshot is taken.
  for diagnostic_name in picker camera-cancel camera-capture recipe-flow fitness-flow; do
    for diagnostic_format in xml png; do
      diagnostic_output="app/build/outputs/device-diagnostics/$diagnostic_name.$diagnostic_format"
      if adb pull "/sdcard/Download/eg-synthetic-test-diagnostics/$diagnostic_name.$diagnostic_format" \
          "$diagnostic_output.tmp" >/dev/null 2>&1; then
        mv "$diagnostic_output.tmp" "$diagnostic_output"
      else
        rm -f "$diagnostic_output.tmp"
      fi
    done
  done
fi
exit "$device_test_result"
