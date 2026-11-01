#!/usr/bin/env python3
"""Verify shared synthetic FEFO/needs TSV against pinned Python project_needs.

The Android Kotlin test consumes the exact same TSV; this checker runs the
independent, pinned upstream function rather than reimplementing its algorithm.
"""
import csv
import importlib.util
import sys
from pathlib import Path


def optional(value):
    return None if value == "-" else value


def quantity(parts, index):
    return {
        "amount": int(parts[index]) if parts[index] != "-" else None,
        "unit": optional(parts[index + 1]),
        "basis": optional(parts[index + 2]),
        "packageCount": int(parts[index + 3]) if parts[index + 3] != "-" else None,
        "confirmed": parts[index + 4] == "true",
    }


def stock(value):
    if value == "~":
        return None
    if value == "-":
        return []
    rows = []
    for entry in value.split(";"):
        p = entry.split("|")
        assert len(p) == 10, p
        rows.append({
            "lotId": optional(p[0]), "foodTypeId": optional(p[1]),
            "quantity": quantity(p, 2), "safe": p[7] == "true",
            "available": p[8] == "true", "usableUntil": optional(p[9]),
        })
    return rows


def demands(value):
    if value == "~":
        return None
    if value == "-":
        return []
    rows = []
    for entry in value.split(";"):
        p = entry.split("|")
        assert len(p) == 9, p
        rows.append({
            "eventId": optional(p[0]), "foodTypeId": optional(p[1]),
            "dueDate": optional(p[2]), "quantity": quantity(p, 3),
            "kind": optional(p[8]),
        })
    return rows


def history(value):
    if value == "-":
        return []
    rows = []
    for entry in value.split(";"):
        p = entry.split("|")
        assert len(p) == 9, p
        rows.append({
            "eventId": optional(p[0]), "foodTypeId": optional(p[1]),
            "date": p[2], "quantity": quantity(p, 3),
            "confirmed": p[7] == "true", "kind": optional(p[8]),
        })
    return rows


def totals(view):
    return ",".join(
        f"{r['foodTypeId']}:{r['unit']}:{r['basis']}={r['quantityToBuy']}"
        for r in view["totals"]
    ) or "-"


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: checker.py PINNED_ENGINE.py SHARED_VECTORS.tsv")
    spec = importlib.util.spec_from_file_location("pinned_shopping_engine", sys.argv[1])
    engine = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(engine)
    count = 0
    with Path(sys.argv[2]).open(encoding="utf-8", newline="") as source:
        for p in list(csv.reader(source, delimiter="\t"))[1:]:
            assert len(p) == 13, p
            snapshot = {
                "asOf": p[1], "stock": stock(p[2]), "demands": demands(p[3]),
                "consumptionHistory": history(p[4]),
            }
            if p[5] != "-":
                snapshot["historyStart"] = p[5]
            if p[6] != "-":
                snapshot["historyUntil"] = p[6]
            result = engine.project_needs(snapshot)
            for column, horizon in ((7, 1), (8, 7), (9, 28), (10, 56)):
                actual = totals(result["horizons"][str(horizon)])
                assert actual == p[column], (p[0], horizon, actual, p[column])
            unknown = ",".join(x["eventId"] for x in result["unknownNeeds"]) or "-"
            assert unknown == p[11], (p[0], "unknown", unknown, p[11])
            assert result["snapshotComplete"] == (p[12] == "true"), p[0]
            assert result["sideEffects"] == [], p[0]
            assert all(0 <= n["quantityToBuy"] <= n["quantityRequired"]
                       for n in result["allocations"]), p[0]
            count += 1
    assert count == 12, count
    print(f"SHOPPING_NEEDS_PYTHON_PARITY_PASS: {count}/{count} shared project_needs cases")


if __name__ == "__main__":
    main()
