"""Actual subprocess refusal checks: no install, launch or private read occurs."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class SignedApkRefusalTest(unittest.TestCase):
    def refusal(self, qemu, installed):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            script = root / "adb"
            script.write_text("#!/bin/sh\n" +
                'echo "$*" >> "$EG_SMOKE_CALLS"\n' +
                'case "$*" in\n' +
                '*ro.kernel.qemu) echo ' + str(qemu) + ';;\n' +
                '*ro.build.version.sdk) echo 35;;\n' +
                '*"pm path"*) echo "' + installed + '";;\n' +
                '*) echo "UNAUTHORIZED MUTATION OR READ"; exit 9;;\nesac\n')
            script.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"], EG_SMOKE_CALLS=str(root / "calls"))
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("check_signed_apk_smoke.py")), "missing-metadata.json"],
                env=env, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Refusing", result.stderr)
            calls = (root / "calls").read_text()
            self.assertNotIn("install", calls)
            self.assertNotIn("run-as", calls)
            self.assertNotIn("force-stop", calls)
            self.assertNotIn("am start", calls)

    def test_physical_device_is_refused_before_metadata_or_application_access(self):
        self.refusal(0, "")

    def test_existing_emulator_installation_is_preserved_without_any_update_or_data_read(self):
        self.refusal(1, "package:/data/app/preserved/base.apk")


if __name__ == "__main__":
    unittest.main()
