#!/usr/bin/env python3
"""Inspect actual freshly built subject/test APKs; never install or extract.

The shared price vectors belong only to instrumentation. Preserve a small
source/APK-bound packaging proof, not APK bytes or private application data.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile


def main(subject, test, output):
    prefix = "assets/published_sources/"
    wanted = {prefix + f for f in ("rewe.json", "aldi_sued.json", "netto.html", "reference_expected.json")}
    def entries(path):
        with zipfile.ZipFile(path) as archive:
            return [name for name in archive.namelist() if name.startswith(prefix) and not name.endswith("/")]
    assert entries(subject) == [], "Synthetic source assets must not ship in the subject APK"
    actual = entries(test)
    assert set(actual) == wanted and len(actual) == len(wanted), actual
    proof = {"format": "PUBLISHED_SOURCE_TEST_ASSETS_V1", "status": "PASS",
             "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
             "subjectApkSha256": hashlib.sha256(Path(subject).read_bytes()).hexdigest(),
             "testApkSha256": hashlib.sha256(Path(test).read_bytes()).hexdigest(),
             "subjectFixtureCount": 0, "testFixtureCount": len(actual)}
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, indent=2) + "\n")
    print("PUBLISHED_SOURCE_TEST_ASSETS_PASS: zero subject fixtures; four test-only inputs; source/APK bound")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: check_published_source_test_assets.py subject.apk test.apk proof.json")
    main(*sys.argv[1:])
