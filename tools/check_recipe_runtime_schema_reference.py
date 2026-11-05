#!/usr/bin/env python3
"""Same pinned recipe.draft vectors used by the offline Android runtime gate."""
import hashlib
import json
import sys
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

PIN = "bedde4677342817fe6b883962c8dfbbd60100072"


def main(schema_path, cases_path):
    blob = Path(schema_path).read_bytes()
    assert hashlib.sha1(b"blob " + str(len(blob)).encode() + b"\0" + blob).hexdigest() == PIN
    schema = json.loads(blob)
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    counts = {True: 0, False: 0}
    for line in Path(cases_path).read_text(encoding="utf-8").splitlines():
        case = json.loads(line)
        expected = case["valid"]
        assert type(expected) is bool
        actual = validator.is_valid(case["message"])
        assert actual == expected, case["name"]
        counts[expected] += 1
    assert counts[True] > 0 and counts[False] > 0
    print(f"RECIPE_RUNTIME_SCHEMA_PARITY_PASS: {sum(counts.values())} vectors "
          f"({counts[True]} valid / {counts[False]} invalid)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: check_recipe_runtime_schema_reference.py schema.json cases.jsonl")
    main(*sys.argv[1:])
