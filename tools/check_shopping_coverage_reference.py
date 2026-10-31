#!/usr/bin/env python3
"""Actual pinned Python _coverage_gaps parity; signal cases are Kotlin contract tests."""
import csv
import runpy
import sys

engine = runpy.run_path(sys.argv[1])
assert tuple(engine["SIGNALS"]) == (
    "RED_SAME_BIO_CHEAPER", "ORANGE_DIFFERENT_BIO_CHEAPER", "GREY_MAY_BE_CHEAPER"
)
with open(sys.argv[2], encoding="utf-8", newline="") as f:
    rows = list(csv.DictReader(f, delimiter="\t"))
assert len(rows) >= 20, "coverage parity fixtures missing"

def values(s):
    return [] if s == "-" else s.split("|")

for row in rows:
    complete = row["complete"]
    coverage = None if complete == "-" else {
        "complete": {"true": True, "false": False, "unknown": None}[complete],
        "gaps": values(row["coverageGaps"]),
    }
    actual = engine["_coverage_gaps"](coverage)
    expected = values(row["expectedCoverageGaps"])
    assert actual == expected, f"{row['id']}: Python={actual}, fixture={expected}"
print(f"PASS: {len(rows)}/{len(rows)} pinned shopping coverage reference vectors")
