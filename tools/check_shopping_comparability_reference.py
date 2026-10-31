#!/usr/bin/env python3
"""Pinned Python _comparability parity; no rights/retailer/purchase authorization."""
import csv
import runpy
import sys

engine = runpy.run_path(sys.argv[1])
with open(sys.argv[2], encoding="utf-8", newline="") as f:
    rows = list(csv.DictReader(f, delimiter="\t"))
assert len(rows) >= 29, "insufficient comparability parity cases"

def n(x):
    return None if x == "-" else x

def attrs(x):
    return {} if x == "-" else dict(part.split("=", 1) for part in x.split(";"))

def article(row, prefix):
    q = {
        "amount": int(row[prefix+"Amount"]) if row[prefix+"Amount"] != "-" else None,
        "unit": n(row[prefix+"Unit"]),
        "basis": n(row[prefix+"Basis"]),
        "packageCount": int(row[prefix+"Count"]) if row[prefix+"Count"] != "-" else None,
        "confirmed": row[prefix+"Confirmed"] == "true",
    }
    return {
        "foodTypeId": n(row[prefix+"Type"]),
        "attributes": attrs(row[prefix+"Attrs"]),
        "quantity": q,
        "gtin": n(row[prefix+"Gtin"]),
        "brandKind": n(row[prefix+"BrandKind"]),
        "brandName": n(row[prefix+"BrandName"]),
    }

for row in rows:
    target = {"observationId": "target", "article": article(row, "t")}
    candidate = {"observationId": "candidate", "article": article(row, "c")}
    matches = []
    if row["matchStatus"] != "-":
        matches = [{
            "targetId": "target", "candidateId": "candidate",
            "status": row["matchStatus"], "matchLevel": row["matchLevel"],
            "evidence": [] if row["matchEvidence"] == "-" else row["matchEvidence"].split("|"),
        }]
    try:
        level, evidence = engine["_comparability"](target, candidate, matches)
        actual = (level, "|".join(evidence) or "-")
    except engine["DomainError"]:
        actual = ("ERROR", "-")
    expected = (row["expectedLevel"], row["expectedNotes"])
    assert actual == expected, f"{row['id']}: Python={actual}, fixture={expected}"
print(f"PASS: {len(rows)}/{len(rows)} pinned shopping comparability parity vectors")
