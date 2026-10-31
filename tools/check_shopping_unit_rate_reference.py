#!/usr/bin/env python3
"""Cross-check exact host TSV vectors against a pinned EG-Einkaufsintelligenz engine.py."""
import csv
import runpy
import sys
from fractions import Fraction

engine = runpy.run_path(sys.argv[1])
rows = list(csv.DictReader(open(sys.argv[2], encoding="utf-8"), delimiter="\t"))
assert len(rows) >= 15, "insufficient parity vectors"

def integer(x):
    return None if x == "-" else int(x)

for row in rows:
    q = dict(amount=integer(row["amount"]), unit=row["unit"],
             basis=row["basis"], packageCount=integer(row["count"]),
             confirmed=row["confirmed"] == "true")
    obs = dict(article={"quantity": q}, priceCents=integer(row["price"]),
               currency=row["currency"], shippingCents=integer(row["shipping"]))
    try:
        result = engine["_rate"](obs, online_shipping=row["useShipping"] == "true")
        if result is None:
            actual = ("UNKNOWN", "-", "-", "-", "-")
        else:
            rate, basis, _ = result
            milli = (rate.numerator * 2000 + rate.denominator) // (2 * rate.denominator)
            actual = ("OK", str(rate.numerator), str(rate.denominator), basis, str(milli))
    except engine["DomainError"]:
        actual = ("ERROR", "-", "-", "-", "-")
    expected = tuple(row[k] for k in ("status", "numerator", "denominator", "displayBasis", "milli"))
    assert actual == expected, f"{row['id']}: Python={actual} fixture={expected}"
print(f"PASS: {len(rows)}/{len(rows)} pinned shopping quantity/rate parity vectors")
