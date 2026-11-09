#!/usr/bin/env python3
"""Execute the exact signed milestone on a fresh disposable emulator.

Never replaces an existing installation, clears data, uninstalls, grants health
rights, or reads a physical device. Only the newly installed empty watch-test
package is launched, navigated, stopped and inspected. No database is exported
as an artifact; only empty-fact counts and schema/provenance evidence survive.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PACKAGE = "de.ernaehrung.demo.watchspike"
CERTIFICATE = "03a27323cc90758409fbc5a7d370f222e01b030671a288082b43d09032c72857"
ADB = ["adb", "-s", "emulator-5554"]


def command(args, check=True, binary=False):
    result = subprocess.run(args, capture_output=True, timeout=30, check=check)
    return result.stdout if binary else result.stdout.decode().strip()


def guard():
    if command(ADB + ["shell", "getprop", "ro.kernel.qemu"]) != "1":
        raise RuntimeError("Refusing signed APK smoke outside the disposable emulator")
    sdk = int(command(ADB + ["shell", "getprop", "ro.build.version.sdk"]))
    if sdk not in (26, 35):
        raise RuntimeError("Unexpected emulator SDK")
    if command(ADB + ["shell", "pm", "path", PACKAGE], check=False):
        raise RuntimeError("Refusing to replace an existing watch-test installation")
    return sdk


def main(metadata_file):
    sdk = guard()  # No install or application-file read may precede this guard.
    metadata_path = Path(metadata_file)
    meta = json.loads(metadata_path.read_text())
    assert meta["package"] == PACKAGE and meta["versionCode"] > 0
    assert meta["signerCertificateSha256"] == CERTIFICATE
    assert re.fullmatch("[0-9a-f]{40}", meta["sourceCommit"])
    assert Path(meta["apkFile"]).name == meta["apkFile"]
    output = Path("app/build/outputs/device-diagnostics/watch-apk")
    output.mkdir(parents=True, exist_ok=True)
    tree = command(["git", "rev-parse", "HEAD:app"])
    if tree != meta["applicationTreeSha"]:
        # Later functional commits must not build a new APK for every step or
        # pretend an older hosted milestone tested their new application code.
        proof = dict(status="NOT_APPLICABLE", reason="Hosted milestone has a different application tree; no installation attempted",
            actualSdk=sdk, sourceCommit=command(["git", "rev-parse", "HEAD"]), apkSourceCommit=meta["sourceCommit"])
        (output / "proof.json").write_text(json.dumps(proof, indent=2) + "\n")
        print("SIGNED_APK_NOT_APPLICABLE:", json.dumps(proof, sort_keys=True))
        return
    # A PR checkout may contain only its head. The pinned tree itself is in
    # that checkout even when the APK source commit is outside shallow history.
    available = subprocess.run(["git", "cat-file", "-e", meta["sourceCommit"] + "^{commit}"], capture_output=True).returncode == 0
    if available:
        assert tree == command(["git", "rev-parse", meta["sourceCommit"] + ":app"])
    apk = metadata_path.parent / meta["apkFile"]
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert digest == meta["sha256"] and apk.stat().st_size == meta["bytes"]
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    badging = command([str(build_tools / "aapt"), "dump", "badging", str(apk)])
    assert "package: name='" + PACKAGE + "' versionCode='" + str(meta["versionCode"]) + "'" in badging
    signature = command([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
    assert "certificate SHA-256 digest: " + meta["signerCertificateSha256"] in signature
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
    assert "Verified using v3 scheme (APK Signature Scheme v3): true" in signature
    (output / "signature.txt").write_text(signature + "\n")
    (output / "badging.txt").write_text(badging + "\n")
    command(ADB + ["install", "-r", str(apk)])
    command(ADB + ["shell", "am", "start", "-W", "-n", PACKAGE + "/de.ernaehrung.demo.MainActivity"])
    remote = "/sdcard/Download/eg-synthetic-test-diagnostics/signed-watch-apk.xml"
    command(ADB + ["shell", "mkdir", "-p", str(Path(remote).parent)])

    def ui():
        command(ADB + ["shell", "uiautomator", "dump", remote])
        raw = command(ADB + ["exec-out", "cat", remote], binary=True)
        root = ET.fromstring(raw)
        assert any(n.get("package") == PACKAGE for n in root.iter("node"))
        (output / "screen.xml").write_bytes(raw)
        return root

    def tap(text, scroll=False):
        for _ in range(6):
            root = ui()
            for node in root.iter("node"):
                if text not in (node.get("text"), node.get("content-desc")):
                    continue
                numbers = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                if len(numbers) == 4 and numbers[0] < numbers[2] and numbers[1] < numbers[3]:
                    command(ADB + ["shell", "input", "tap", str((numbers[0] + numbers[2]) // 2), str((numbers[1] + numbers[3]) // 2)])
                    return
            if not scroll:
                time.sleep(0.3)
                continue
            bounds = [list(map(int, re.findall(r"\d+", n.get("bounds", "")))) for n in root.iter("node")]
            width = max(b[2] for b in bounds if len(b) == 4)
            height = max(b[3] for b in bounds if len(b) == 4)
            command(ADB + ["shell", "input", "swipe", str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), "300"])
        raise RuntimeError("Signed APK control not reachable: " + text)

    tap("Mehr")
    tap("Weitere Gesundheitsdaten", scroll=True)
    for _ in range(6):
        root = ui()
        if any(n.get("text", "").startswith("Wähle die Datentypen") for n in root.iter("node")):
            break
        time.sleep(0.3)
    else:
        raise RuntimeError("Signed APK additional-health screen was not reached")
    pid = command(ADB + ["shell", "pidof", PACKAGE])
    assert pid and all(part.isdigit() for part in pid.split())
    command(ADB + ["shell", "am", "force-stop", PACKAGE])
    assert not command(ADB + ["shell", "pidof", PACKAGE], check=False)
    with tempfile.TemporaryDirectory(prefix="eg-empty-apk-") as tmp:
        base = Path(tmp) / "empty.db"
        for suffix in ("", "-wal", "-shm"):
            result = subprocess.run(ADB + ["exec-out", "run-as", PACKAGE, "cat", "databases/food-local-sandbox.db" + suffix],
                capture_output=True, timeout=20)
            if result.returncode == 0:
                Path(str(base) + suffix).write_bytes(result.stdout)
            elif not suffix:
                raise RuntimeError("New signed APK did not create its local Room database")
        with sqlite3.connect(base) as db:
            version = db.execute("PRAGMA user_version").fetchone()[0]
            assert version == meta["schema"]
            schema = json.loads(Path("app/schemas/de.ernaehrung.demo.data.local.FoodDatabase/" + str(version) + ".json").read_text())["database"]
            identity = db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()[0]
            assert identity == schema["identityHash"]
            tables = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')").fetchall()
            assert len(tables) == len(schema["entities"])
            empty = ["health_extra_state", "health_extra_runs", "health_extra_versions", "health_extra_samples",
                "health_import_state", "health_import_runs", "health_source_record_versions", "consumption_events", "purchase_events", "stock_movements"]
            assert all(db.execute('SELECT COUNT(*) FROM "' + table + '"').fetchone()[0] == 0 for table in empty)
            assert not db.execute("PRAGMA foreign_key_check").fetchall()
    proof = dict(status="PASS", format="SIGNED_APK_UI_SCHEMA_V1", actualSdk=sdk, package=PACKAGE,
        versionCode=meta["versionCode"], apkSha256=digest, signerCertificateSha256=meta["signerCertificateSha256"],
        sourceCommit=command(["git", "rev-parse", "HEAD"]), apkSourceCommit=meta["sourceCommit"], applicationTreeSha=tree,
        startedPid=pid, forceStopObserved=True, schema=version, tables=len(tables), extraScreenReached=True,
        healthConsentCreated=False, actualFactsCreated=False, physicalDevice=False)
    (output / "proof.json").write_text(json.dumps(proof, indent=2) + "\n")
    print("SIGNED_APK_UI_SCHEMA_PASS:", json.dumps(proof, sort_keys=True))


if __name__ == "__main__":
    main(sys.argv[1])
