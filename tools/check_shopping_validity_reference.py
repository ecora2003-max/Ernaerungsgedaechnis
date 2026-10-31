#!/usr/bin/env python3
"""Pinned Python _valid/_scope_ok parity, not source-rights or booking authorization."""
import csv
from datetime import datetime
import runpy
import sys

engine = runpy.run_path(sys.argv[1])
with open(sys.argv[2], encoding="utf-8", newline="") as handle:
    rows = list(csv.DictReader(handle, delimiter="\t"))
assert len(rows) >= 25, "insufficient validity parity vectors"

def nullable(value):
    return None if value == "-" else value

def integer(value):
    return None if value == "-" else int(value)

for row in rows:
    observation = {
        "priceKind": nullable(row["kind"]),
        "priceCents": integer(row["price"]),
        "availability": nullable(row["availability"]),
        "conditional": row["conditional"] == "true",
        "source": {
            "dataQuality": nullable(row["quality"]),
            "rightsStatus": nullable(row["rights"]),
            "observedAt": nullable(row["observedAt"]),
        },
        "scope": {
            "retailerId": nullable(row["retailer"]),
            "scopeType": nullable(row["scopeType"]),
            "storeId": nullable(row["store"]),
        },
        "validFrom": nullable(row["validFrom"]),
        "validUntil": nullable(row["validUntil"]),
        "shippingCents": integer(row["shipping"]),
    }
    allowed = None
    if row["allowedRetailer"] != "-":
        allowed = {row["allowedRetailer"]: (
            row["allowedStore"].split(",") if row["allowedStore"] != "-" else []
        )}
    try:
        eligible, notes = engine["_valid"](
            observation, engine["_day"](row["shoppingDate"]),
            engine["_instant"](row["asOf"]),
            historical=row["historical"] == "true",
            allow_online=row["allowOnline"] == "true",
            allowed_stores=allowed,
        )
        actual = ("PASS" if eligible else "REJECT", "|".join(notes) or "-")
    except engine["DomainError"]:
        actual = ("ERROR", "-")
    expected = (row["status"], row["notes"])
    assert actual == expected, f"{row['id']}: Python={actual}, fixture={expected}"
print(f"PASS: {len(rows)}/{len(rows)} pinned shopping validity/scope parity vectors")
