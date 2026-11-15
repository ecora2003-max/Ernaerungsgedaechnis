"""Actual subprocess refusal checks: no install, launch or private read occurs."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from check_signed_apk_smoke import SOURCE_PATHS, source_compatible, interaction_owner


class SignedApkControlTest(unittest.TestCase):
    def test_enabled_android_label_does_not_reenable_its_disabled_compose_button(self):
        root = ET.fromstring('<node enabled="true"><node clickable="true" enabled="false"><node text="Knuspr verbinden" clickable="false" enabled="true"/></node></node>')
        label = next(n for n in root.iter() if n.get("text"))
        self.assertEqual("false", interaction_owner(root, label).get("enabled"))

    def test_nearest_interactive_control_wins_over_unrelated_disabled_outer_container(self):
        root = ET.fromstring('<node clickable="true" enabled="false"><node clickable="true" enabled="true"><node text="Knuspr verbinden" enabled="false"/></node></node>')
        label = next(n for n in root.iter() if n.get("text"))
        self.assertEqual("true", interaction_owner(root, label).get("enabled"))

    def test_plain_disabled_text_cannot_prove_a_disabled_interactive_control(self):
        root = ET.fromstring('<node><node text="Knuspr verbinden" enabled="false"/></node>')
        with self.assertRaises(ValueError):
            interaction_owner(root, root[0])


class SignedApkSourceTest(unittest.TestCase):
    def metadata(self):
        trees = {name: "a" * 40 for name in SOURCE_PATHS}
        return dict(provenanceFormat="EG_SIGNED_APK_SOURCE_V2", applicationTreeSha=trees["app"], buildSourceTrees=trees)

    def test_exact_complete_build_source_is_accepted(self):
        meta = self.metadata()
        self.assertTrue(source_compatible(meta, dict(meta["buildSourceTrees"])))

    def test_changed_nano_or_shared_build_with_same_app_cannot_claim_an_older_apk(self):
        meta = self.metadata()
        for name in SOURCE_PATHS:
            if name != "app":
                actual = dict(meta["buildSourceTrees"])
                actual[name] = "b" * 40
                self.assertFalse(source_compatible(meta, actual), name)

    def test_legacy_app_only_metadata_cannot_prove_the_complete_build(self):
        self.assertFalse(source_compatible(dict(applicationTreeSha="a" * 40), self.metadata()["buildSourceTrees"]))

    def test_incomplete_or_conflicting_current_provenance_is_rejected(self):
        for change in (lambda m: m["buildSourceTrees"].pop("nano-runtime"),
                       lambda m: m.update(applicationTreeSha="b" * 40),
                       lambda m: m["buildSourceTrees"].update(gradle="not-a-git-object")):
            meta = self.metadata()
            change(meta)
            with self.assertRaises(ValueError):
                source_compatible(meta, self.metadata()["buildSourceTrees"])


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
