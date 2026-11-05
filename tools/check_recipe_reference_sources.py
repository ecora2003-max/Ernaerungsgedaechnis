#!/usr/bin/env python3
"""Verify pinned sources, original assertions and one explicit Android 26 patch.

Hashes were taken from EG-Rezept-KI@6581eabd, after verifying/restoring its
SHA256-pinned transfer archive. This check performs no network calls or writes.
The original assertions also run inside the host JVM suite.
"""
import hashlib
import json
from pathlib import Path


def main():
    root = Path(__file__).resolve().parent.parent
    manifest = json.loads((root / "tools/recipe_reference_v1_manifest.json").read_text())
    if manifest.get("repository") != "ecora2003-max/EG-Rezept-KI" or manifest.get("commit") != \
            "6581eabd5c9c38e387fb0b73f4c9747b75d323e3":
        raise SystemExit("Unapproved recipe module pin; explicit integration review required")
    if manifest.get("coreArchiveSha256") != "a5c53a77dfdf74d0cfc6497db5f415cf2abb0a32e7a3a0363ca2dc9f59581838":
        raise SystemExit("Recipe source archive pin changed")
    names = ("RecipeEngine", "CombinatorialGenerator", "RecipeDraftImport", "RecipeDraftRevalidation")
    paths = {f"app/src/{kind}/java/de/eg/recipe/{name}{suffix}.kt"
             for name in names for kind, suffix in (("main", ""), ("test", "Test"))}
    hashes = manifest.get("sha256", {})
    if hashes.keys() != paths:
        raise SystemExit("Reference source manifest scope changed")
    adaptations = 0
    for path in sorted(paths):
        data = (root / path).read_bytes()
        if path == "app/src/main/java/de/eg/recipe/RecipeDraftImport.kt":
            # The original complete-file hash also verifies the unchanged
            # sign/bit-length guard. No truncation can pass that guard.
            patch = b"            v.toLong() // Android 26: exact after the unchanged sign/bit-length guard above.\n"
            if data.count(patch) == 1:
                data = data.replace(patch, b"            v.longValueExact()\n")
                adaptations += 1
        digest = hashlib.sha256(data).hexdigest()
        if digest != hashes[path]:
            raise SystemExit(f"Pinned upstream source differs: {path}")
    print(f"RECIPE_REFERENCE_SOURCE_PASS: {len(paths)}/{len(paths)} pinned source/test files, "
          f"{adaptations} explicit API26 compatibility adaptation")


if __name__ == "__main__":
    main()
