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
  # Capture the picker at its own failure boundary; later tests can replace
  # that OS surface before the final workflow screenshot is taken.
  for picker_diagnostic_format in xml png; do
    if adb shell run-as de.ernaehrung.demo test -f "files/picker-diagnostic.$picker_diagnostic_format"; then
      adb exec-out run-as de.ernaehrung.demo cat "files/picker-diagnostic.$picker_diagnostic_format" \
        > "app/build/outputs/device-diagnostics/picker.$picker_diagnostic_format"
    fi
  done
fi
exit "$device_test_result"
