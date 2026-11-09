#!/usr/bin/env bash
# Extra real-process proof after the ordinary JUnit suite. No original data is
# removed, no signature mismatch is bypassed, and no physical device is touched.
set -euo pipefail
subject_package=de.ernaehrung.demo
test_package=de.ernaehrung.demo.test
device_serial=emulator-5554
device_evidence=app/build/outputs/device-diagnostics
adb_cmd=(adb -s "$device_serial")
qemu=$(timeout 20s "${adb_cmd[@]}" shell getprop ro.kernel.qemu | tr -d '\r')
[[ "$qemu" == 1 ]] || { echo 'Refusing process proof outside the disposable emulator.' >&2; exit 1; }
mkdir -p "$device_evidence"

capture_failure() {
  local result=$?
  trap - EXIT
  if [[ "$result" -ne 0 ]]; then
    set +e
    timeout 15s "${adb_cmd[@]}" logcat -d -v threadtime -t 600 > "$device_evidence/process-restart-failure-logcat.txt" 2>&1
    timeout 15s "${adb_cmd[@]}" shell dumpsys activity activities > "$device_evidence/process-restart-activities.txt" 2>&1
    timeout 15s "${adb_cmd[@]}" shell dumpsys connectivity > "$device_evidence/process-restart-connectivity.txt" 2>&1
  fi
  exit "$result"
}
trap capture_failure EXIT
source_sha=$(git rev-parse HEAD)
proof_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
subject_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
test -f "$subject_apk"
test -f "$test_apk"
# connectedAndroidTest may remove its APKs. Reinstall the existing exact files
# with -r only; never uninstall or clear data to get around a signing problem.
timeout 60s "${adb_cmd[@]}" install -r "$subject_apk"
timeout 60s "${adb_cmd[@]}" install -r -t "$test_apk"

run_phase() {
  local phase=$1
  local output="$device_evidence/process-restart-$phase.txt"
  timeout --signal=TERM --kill-after=10s 90s "${adb_cmd[@]}" shell am instrument -w -r \
    -e phase "$phase" -e proof_id "$proof_id" -e source_sha "$source_sha" \
    "$test_package/de.ernaehrung.demo.SyntheticPersistenceInstrumentation" > "$output" 2>&1
  # am instrument can return shell exit 0 even when instrumentation fails.
  # Require the original result code and matching phase/run/source evidence.
  python3 - "$output" "$phase" "$proof_id" "$source_sha" <<'PY'
from pathlib import Path
import sys
path, phase, proof, source = sys.argv[1:]
lines = Path(path).read_text().splitlines()
assert 'INSTRUMENTATION_CODE: -1' in lines, '\n'.join(lines)
results = dict(line.removeprefix('INSTRUMENTATION_RESULT: ').split('=', 1)
               for line in lines if line.startswith('INSTRUMENTATION_RESULT: ') and '=' in line)
assert results['EG_RESTART_STATUS'] == 'PASS', results
assert results['EG_RESTART_PHASE'] == phase and results['EG_RESTART_PROOF'] == proof and results['EG_RESTART_SOURCE'] == source
assert int(results['EG_RESTART_PID']) > 0 and results['EG_RESTART_SDK'] in ('26', '35'), results
print('Actual process phase:', phase, 'PASS; PID', results['EG_RESTART_PID'], 'SDK', results['EG_RESTART_SDK'])
PY
}
run_phase seed
timeout 30s "${adb_cmd[@]}" shell am start -W -n "$subject_package/de.ernaehrung.demo.MainActivity" > "$device_evidence/process-restart-first-launch.txt"
timeout 10s "${adb_cmd[@]}" shell pidof "$subject_package" > "$device_evidence/process-restart-first-pid.txt"
timeout 20s "${adb_cmd[@]}" shell am force-stop "$subject_package"
if timeout 10s "${adb_cmd[@]}" shell pidof "$subject_package" > "$device_evidence/process-restart-stopped-pid.txt"; then
  echo 'Subject process survived am force-stop.' >&2
  exit 1
else
  stopped_result=$?
  [[ "$stopped_result" == 1 ]] || exit "$stopped_result"
fi
timeout 30s "${adb_cmd[@]}" shell am start -W -n "$subject_package/de.ernaehrung.demo.MainActivity" > "$device_evidence/process-restart-second-launch.txt"
timeout 10s "${adb_cmd[@]}" shell pidof "$subject_package" > "$device_evidence/process-restart-second-pid.txt"
run_phase read
python3 - "$device_evidence" "$source_sha" "$proof_id" "$subject_apk" "$test_apk" <<'PY'
from pathlib import Path
import sys, json, hashlib
directory, source, proof, subject_apk, test_apk = sys.argv[1:]
root = Path(directory)
first = (root / 'process-restart-first-pid.txt').read_text().strip()
second = (root / 'process-restart-second-pid.txt').read_text().strip()
assert first.isdigit() and second.isdigit() and first != second, (first, second)
assert not (root / 'process-restart-stopped-pid.txt').read_text().strip()
def phase(name):
    return dict(line.removeprefix('INSTRUMENTATION_RESULT: ').split('=', 1)
                for line in (root / ('process-restart-' + name + '.txt')).read_text().splitlines()
                if line.startswith('INSTRUMENTATION_RESULT: ') and '=' in line)
seed, read = phase('seed'), phase('read')
assert seed['EG_RESTART_PID'] != read['EG_RESTART_PID']
assert seed['EG_RESTART_SDK'] == read['EG_RESTART_SDK']
data = dict(format='android-actual-process-restart-v1', status='PASS', sourceCommit=source, proofId=proof, actualSdk=int(read['EG_RESTART_SDK']),
            subjectPackage='de.ernaehrung.demo', mainPidBefore=int(first), mainPidAfter=int(second),
            seedPid=int(seed['EG_RESTART_PID']), readPid=int(read['EG_RESTART_PID']),
            forceStopObserved=True, subjectApkSha256=hashlib.sha256(Path(subject_apk).read_bytes()).hexdigest(),
            testApkSha256=hashlib.sha256(Path(test_apk).read_bytes()).hexdigest(),
            checks=['confirmed stock/consumption', 'historical versions', 'null != 0', 'idempotent retry',
                    'unconfirmed operation rejected', 'FK', 'actual network absence',
                    'private OAuth keystore persistence/replay rejection', 'durable automatic-refresh floor/default deny', 'private Gemini key/mode persistence without inference',
                    'synthetic deferred health intervals/raw times/retry/aggregate/disabled consent persist'])
(root / 'process-restart.json').write_text(json.dumps(data, indent=2) + '\n')
print('ANDROID_ACTUAL_PROCESS_RESTART_PASS:', data['actualSdk'], first, '->', second)
PY
