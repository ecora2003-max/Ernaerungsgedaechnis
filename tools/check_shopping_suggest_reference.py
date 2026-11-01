#!/usr/bin/env python3
"""Shared bounded full-call suggest_purchases fixtures against pinned Python engine.

The Kotlin host applies stronger independently authenticated APP_DISPLAY gates.
Compare only proven synthetic fully authorized purchase math/mix/status fields,
not source-policy outputs or untested error/unknown cases.
"""
import csv
import pathlib
import sys

from eg_shopping.engine import suggest_purchases

DATE = "2026-11-01"
AS_OF = "2026-10-31T12:00:00+01:00"
OBSERVED = "2026-10-31T10:00:00+01:00"
DUE = "2026-11-02"


def parse_demands(raw):
    totals, needs = [], []
    for entry in raw.split(";"):
        food, amount = entry.split(":")
        item = {"foodTypeId": food, "unit": "g", "basis": "NET",
                "quantityToBuy": int(amount)}
        totals.append(item)
        needs.append(dict(item, dueDate=DUE))
    return {"totals": totals, "needs": needs}


def parse_offers(raw):
    products = []
    for entry in raw.split(";"):
        oid, food, amount, cents, deposit, minimum = entry.split(":")
        q = {"amount": int(amount), "unit": "g", "basis": "NET",
             "packageCount": 1, "confirmed": True}
        evidence = {
            "observationId": oid,
            "article": {"foodTypeId": food, "quantity": q, "productRef": oid},
            "scope": {"retailerId": "LIDL", "storeId": "store-1",
                      "scopeType": "STORE_CONFIRMED"},
            "priceCents": int(cents), "depositCents": int(deposit),
            "currency": "EUR", "priceKind": "SALE",
            "validFrom": DATE, "validUntil": "2026-11-05",
            "source": {"origin": "synthetic", "observedAt": OBSERVED,
                       "dataQuality": "CONFIRMED", "rightsStatus": "UNVERIFIED"},
            "availability": "AVAILABLE", "conditional": [],
        }
        products.append({
            "productId": oid, "foodTypeId": food, "quantity": q,
            "priceCents": int(cents), "depositCents": int(deposit),
            "shelfLifeDays": 5, "storageClass": "DRY",
            "packageVolumeMl": 100, "minPacks": int(minimum),
            "termsVerified": True, "priceObservation": evidence,
        })
    return products


def nullable(value):
    return "-" if value is None else str(value)


def run(row):
    budget = None if row["budget"] == "-" else {
        "amountCents": int(row["budget"]),
        "periodStart": DATE, "periodEnd": "2026-11-07"}
    capacity = None if row["capacity"] == "-" else {
        "DRY": int(row["capacity"])}
    result = suggest_purchases(
        parse_demands(row["demands"]), parse_offers(row["offers"]),
        shopping_date=DATE, as_of=AS_OF, budget=budget, capacity=capacity,
        usage_context="RESEARCH_ONLY")
    actual = {
        "status": ";".join(item["status"] for item in result["items"]),
        "mix": ";".join(
            f'{mix["observationId"]}:{mix["packs"]}'
            for item in result["items"] for mix in item.get("mix", [])),
        "merchandise": nullable(result["totalCostCents"]),
        "checkout": nullable(result["totalCheckoutCents"]),
        "remaining": nullable(result["budgetRemainingCents"]),
    }
    expected = {key: row[key] for key in actual}
    assert actual == expected, f'{row["id"]}: {actual!r} != {expected!r}'
    assert result["sideEffects"] == [], row["id"]


with pathlib.Path(sys.argv[1]).open(newline="", encoding="utf-8") as file:
    rows = list(csv.DictReader(file, delimiter="\t"))
assert len(rows) == 7, len(rows)
for row in rows:
    run(row)
print(f"SHOPPING_SUGGEST_PYTHON_PARITY_PASS: {len(rows)}/{len(rows)} shared suggest_purchases calls")
