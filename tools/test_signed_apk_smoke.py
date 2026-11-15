"""Actual subprocess refusal checks: no install, launch or private read occurs."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import sqlite3
import shutil
import xml.etree.ElementTree as ET
from check_signed_apk_smoke import SOURCE_PATHS, source_compatible, interaction_owner, schema_snapshot


class SignedApkSchemaReadinessTest(unittest.TestCase):
    schema = dict(version=20, identityHash="synthetic-room-identity", entities=[dict(tableName="synthetic_facts")])

    def test_missing_or_empty_copy_is_not_a_database_and_is_never_created_by_the_probe(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "empty.db"
            self.assertFalse(schema_snapshot(base, self.schema)["ready"])
            self.assertFalse(base.exists())
            base.touch()
            self.assertFalse(schema_snapshot(base, self.schema)["ready"])
            self.assertEqual(0, base.stat().st_size)

    def test_real_wal_creation_transaction_is_not_ready_until_committed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); original = root / "live.db"
            db = sqlite3.connect(original)
            try:
                db.execute("PRAGMA journal_mode=WAL")
                db.execute("BEGIN")
                db.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execute("INSERT INTO room_master_table VALUES (42, 'synthetic-room-identity')")
                db.execute("CREATE TABLE synthetic_facts (id INTEGER PRIMARY KEY)")
                db.execute("PRAGMA user_version=20")
                def copy(name):
                    target = root / name
                    for suffix in ("", "-wal", "-shm"):
                        path = Path(str(original) + suffix)
                        if path.exists():
                            shutil.copyfile(path, Path(str(target) + suffix))
                    return target
                self.assertFalse(schema_snapshot(copy("before.db"), self.schema)["ready"])
                db.commit()
                state = schema_snapshot(copy("after.db"), self.schema)
                self.assertTrue(state["ready"])
                self.assertEqual(20, state["version"])
            finally:
                db.close()

    def test_wrong_schema_version_identity_or_table_set_cannot_become_ready(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "empty.db"
            db = sqlite3.connect(base)
            try:
                db.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execute("INSERT INTO room_master_table VALUES (42, 'synthetic-room-identity')")
                db.execute("CREATE TABLE synthetic_facts (id INTEGER PRIMARY KEY)")
                db.execute("PRAGMA user_version=19"); db.commit()
                self.assertFalse(schema_snapshot(base, self.schema)["ready"])
                db.execute("PRAGMA user_version=20")
                db.execute("UPDATE room_master_table SET identity_hash='wrong'"); db.commit()
                self.assertFalse(schema_snapshot(base, self.schema)["ready"])
                db.execute("UPDATE room_master_table SET identity_hash='synthetic-room-identity'")
                db.execute("DROP TABLE synthetic_facts"); db.commit()
                self.assertFalse(schema_snapshot(base, self.schema)["ready"])
            finally:
                db.close()


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
