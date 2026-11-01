#!/usr/bin/env python3
"""Run shared synthetic package-mix vectors against pinned Python full function."""
import csv
import importlib.util
import pathlib
import sys

module_path, fixture_path = map(pathlib.Path, sys.argv[1:3])
spec = importlib.util.spec_from_file_location("pinned_pack_optimizer", module_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def candidates(raw):
    result = []
    for entry in raw.split(";"):
        pid, amount, price, shelf, storage, volume = entry.split("|")
        result.append({
            "productId": pid, "amount": int(amount), "priceCents": int(price),
            "shelfLifeDays": None if shelf == "-" else int(shelf),
            "storageClass": None if storage == "-" else storage,
            "packageVolumeMl": None if volume == "-" else int(volume),
        })
    return result


def capacity(raw):
    if raw == "-":
        return None
    return {k: int(v) for k, v in (item.split("=") for item in raw.split(";"))}


with fixture_path.open(newline="", encoding="utf-8") as stream:
    rows = list(csv.DictReader(stream, delimiter="\t"))
assert len(rows) == 7, len(rows)
for row in rows:
    result = module.optimize_package_mix(
        int(row["target"]), candidates(row["candidates"]),
        shopping_date=row["shoppingDate"],
        last_due_date=None if row["lastDue"] == "-" else row["lastDue"],
        budget_cents=None if row["budget"] == "-" else int(row["budget"]),
        capacity_ml=capacity(row["capacity"]), max_nodes=int(row["maxNodes"]),
    )
    actual = {
        "status": result["status"],
        "cost": "-" if result["costCents"] is None else str(result["costCents"]),
        "mix": ",".join(f'{x["productId"]}:{x["packs"]}' for x in result["items"]) or "-",
        "warnings": ";".join(result["warnings"]) or "-",
    }
    expected = {k: row[k] for k in actual}
    assert actual == expected, f'{row["id"]}: {actual} != {expected}'
print(f"SHOPPING_PACK_PYTHON_PARITY_PASS: {len(rows)}/{len(rows)} shared optimize_package_mix calls")
