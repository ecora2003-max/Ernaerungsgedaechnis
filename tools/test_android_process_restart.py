"""Host-only shell rejection tests. No emulator/Android PASS is produced here."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().with_name("run_android_process_restart.sh")
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[3:]
mode = os.environ['EG_PROOF_TEST_MODE']
statefile = Path(os.environ['EG_PROOF_TEST_STATE'])
log = Path(os.environ['EG_PROOF_TEST_CALLS'])
with log.open('a') as output: output.write(json.dumps(args) + '\n')
state = json.loads(statefile.read_text()) if statefile.exists() else {'starts': 0, 'stopped': False}
if args == ['shell', 'getprop', 'ro.kernel.qemu']:
    print('0' if mode == 'physical' else '1')
elif args and args[0] == 'install':
    print('Success')
elif args[:3] == ['shell', 'am', 'instrument']:
    def option(name): return args[args.index(name) + 1]
    phase, proof, source = option('phase'), option('proof_id'), option('source_sha')
    if mode == 'wrong-source': source = 'b' * 40
    values = dict(STATUS='PASS', PHASE=phase, PROOF=proof, SOURCE=source,
                  PID='10' if phase == 'seed' else '30', SDK='35')
    for key, value in values.items(): print('INSTRUMENTATION_RESULT: EG_RESTART_' + key + '=' + value)
    print('INSTRUMENTATION_CODE: ' + ('0' if mode == 'shell-zero-failure' else '-1'))
elif args[:3] == ['shell', 'am', 'start']:
    state['starts'] += 1
    state['stopped'] = False
    print('Status: ok')
elif args[:3] == ['shell', 'am', 'force-stop']:
    state['stopped'] = mode != 'survived'
elif args[:2] == ['shell', 'pidof']:
    if state['stopped']: sys.exit(1)
    print('20' if state['starts'] == 1 or mode == 'same-pid' else '40')
else:
    print('synthetic shell harness diagnostic only')
statefile.write_text(json.dumps(state))
'''


class ProcessRestartShellTest(unittest.TestCase):
    def run_case(self, mode):
        with tempfile.TemporaryDirectory(prefix="eg-process-shell-test-") as directory:
            root = Path(directory)
            binaries = root / "bin"
            binaries.mkdir()
            for name, content in {
                "adb": FAKE_ADB,
                "git": "#!/bin/sh\necho aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n",
            }.items():
                path = binaries / name
                path.write_text(content)
                path.chmod(0o700)
            for name in ("debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"):
                path = root / "app/build/outputs/apk" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"shell fixture only, never an Android APK")
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                       EG_PROOF_TEST_MODE=mode, EG_PROOF_TEST_STATE=str(root / "state"),
                       EG_PROOF_TEST_CALLS=str(root / "calls"))
            result = subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env,
                                    capture_output=True, text=True, timeout=20)
            evidence = root / "app/build/outputs/device-diagnostics/process-restart.json"
            calls = [json.loads(line) for line in (root / "calls").read_text().splitlines()]
            if mode == "success":
                self.assertEqual(0, result.returncode, result.stderr)
                data = json.loads(evidence.read_text())
                self.assertNotEqual(data["mainPidBefore"], data["mainPidAfter"])
                self.assertNotEqual(data["seedPid"], data["readPid"])
            else:
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertFalse(evidence.exists())
            if mode == "physical":
                self.assertEqual([["shell", "getprop", "ro.kernel.qemu"]], calls)

    def test_valid_shell_evidence_is_accepted(self): self.run_case("success")
    def test_physical_device_is_refused_before_install(self): self.run_case("physical")
    def test_shell_zero_does_not_hide_instrumentation_failure(self): self.run_case("shell-zero-failure")
    def test_wrong_source_is_refused(self): self.run_case("wrong-source")
    def test_surviving_process_is_refused(self): self.run_case("survived")
    def test_reused_pid_is_refused(self): self.run_case("same-pid")


if __name__ == "__main__":
    unittest.main()
