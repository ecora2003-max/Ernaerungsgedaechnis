#!/usr/bin/env python3
"""Verify shared TSV vectors with the PINNED Python full compare_prices call.

Run only with a local, pinned eg_shopping package on PYTHONPATH. No network,
production data, live prices, legal rights inference or host write operations.
"""
import csv
import sys
from pathlib import Path
from eg_shopping.engine import compare_prices

DAY = "2026-11-02"
AS_OF = "2026-10-31T12:00:00+01:00"
OBSERVED = "2026-10-31T10:00:00+01:00"
ORIGIN = "synthetic-fixture"
REF = "synthetic-rights"
GRANTS = {ORIGIN: {
    "rightsEvidenceRef": REF, "usageContext": "APP_DISPLAY",
    "retailerId": "LIDL", "hostVerified": True,
    "acquisitionModes": ["PUBLISHED_OFFER_COLLECTION", "TARGETED_PRODUCT_PRICE_QUERY"],
}}


def organic(text):
    if text == "true":
        return True
    if text == "false":
        return False
    if text == "null":
        return None
    raise ValueError(f"invalid organic value: {text}")


def observation(oid, mode, cents, amount, bio, food, deposit):
    return {
        "observationId": oid,
        "acquisitionMode": mode,
        "article": {
            "label": "Synthetic " + food,
            "foodTypeId": food,
            "brandKind": "PRIVATE_LABEL",
            "brandName": "Fixture",
            "organic": organic(bio),
            "attributes": {"state": "liquid"},
            "quantity": {"amount": int(amount), "unit": "g", "basis": "NET",
                         "packageCount": 1, "confirmed": True},
        },
        "scope": {"retailerId": "LIDL", "storeId": "canonical-42",
                  "scopeType": "STORE_CONFIRMED"},
        "priceCents": int(cents),
        "depositCents": None if deposit == "null" else int(deposit),
        "currency": "EUR", "priceKind": "SALE",
        "validFrom": DAY, "validUntil": "2026-11-04",
        "source": {"origin": ORIGIN, "rightsStatus": "PERMITTED",
                   "rightsEvidenceRef": REF, "dataQuality": "CONFIRMED",
                   "observedAt": OBSERVED, "acquisitionMode": mode},
        "availability": "AVAILABLE", "conditional": False,
    }


def expected(text):
    return [] if text == "-" else text.split(",")


def check(path):
    count = 0
    with Path(path).open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            case = row["case"]
            mode = row["mode"]
            target = observation("target", mode, row["targetCents"],
                                 row["targetAmount"], row["targetOrganic"], "milk", "25")
            candidates = []
            for entry in row["candidates"].split(";"):
                fields = entry.split(":")
                assert len(fields) == 6, (case, entry)
                candidates.append(observation(fields[0], mode, fields[1], fields[2],
                                              fields[3], fields[4], fields[5]))
            result = compare_prices(target, candidates, DAY, as_of=AS_OF,
                                    coverage={"complete": True, "gaps": []},
                                    allowed_stores={"LIDL": ["canonical-42"]},
                                    usage_context="APP_DISPLAY", rights_grants=GRANTS)
            alternatives = result["alternatives"]
            actual = {
                "expectedIds": [a["observationId"] for a in alternatives],
                "expectedSignals": result["signals"],
                "expectedDeltas": [str(a["priceDifferenceCents"])
                                   if a["priceDifferenceCents"] is not None else "null"
                                   for a in alternatives],
                "expectedRates": [
                    str(a["exactUnitRateCents"]["numerator"]) + "/" +
                    str(a["exactUnitRateCents"]["denominator"]) for a in alternatives
                ],
            }
            for key, value in actual.items():
                assert value == expected(row[key]), (case, key, value, row[key])
            assert result["incomplete"] == ("GREY_MAY_BE_CHEAPER" in result["signals"]), case
            assert result["bestPriceClaim"] == "CHEAPEST_KNOWN_MATCHING_ONLY", case
            assert not result["rejected"] and not result["otherDayOffers"], case
            count += 1
    assert count == 9, count
    print(f"FULL_COMPARE_PYTHON_PARITY_PASS: {count}/{count} full compare_prices calls")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: check_shopping_full_compare_reference.py TSV")
    check(sys.argv[1])
