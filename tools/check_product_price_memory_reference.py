#!/usr/bin/env python3
"""Parity against pinned original EG-Produktdaten (offline only)."""
import csv
import importlib.util
import pathlib
import sys

def load(path):
    spec = importlib.util.spec_from_file_location("eg_reference_price", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

def inputs():
    receipt = dict(purchaseConfirmed=True, purchaseReversed=False, purchaseId="op-1",
        receiptId="bon-1", retailerId="REWE", confirmedAt="2026-10-31T12:00:00+00:00",
        purchasedOn="2026-10-30", purchaseAt="2026-10-30T09:00:00+00:00", storeId="filiale-1")
    line = dict(confirmed=True, productConfirmed=True, lineKind="FOOD", lineId="position-1",
        receiptId="bon-1", retailerId="REWE", productId="food-1", versionId="v1",
        amountCents=499, purchaseUnits=2, unitsConfirmed=True, discountReconciled=True,
        unallocatedDiscount=False, allocatedDiscountCents=1, discountApplied=True,
        amountExcludesDeposit=True, depositPerUnitCents=25, storeId="filiale-1",
        gtin="3017620422003")
    article = dict(id="food-1", versionId="v1", label="Testtofu", gtin="3017620422003")
    return receipt, line, article

INTS = {"amountCents", "purchaseUnits", "allocatedDiscountCents", "depositPerUnitCents"}
def patch(target, raw):
    for pair in raw.split(";"):
        if not pair: continue
        key, value = pair.split("=", 1)
        if value == "!": target.pop(key, None)
        elif value == "~": target[key] = None
        elif value in ("true", "false"): target[key] = value == "true"
        elif key in INTS: target[key] = int(value)
        else: target[key] = value

def main(reference, fixtures):
    engine = load(reference)
    assert engine.CONTRACT_VERSION == "1.0.0-draft", "unexpected source contract"
    with pathlib.Path(fixtures).open(encoding="utf-8", newline="") as f:
        cases = list(csv.DictReader(f, delimiter="\t"))
    seen = {}
    for row in cases:
        receipt, line, article = inputs()
        for target, name in ((receipt, "receipt"), (line, "line"), (article, "article")):
            patch(target, row[name])
        result = engine.to_historical_price_observation(receipt, line, article)
        obs = result["observation"]
        actual = "OK" if obs is not None else result["reason"]
        assert actual == row["reason"], (row["case"], actual, row["reason"])
        if obs is not None:
            assert obs["priceKind"] == "HISTORICAL_PAID" and obs["validFrom"] is None and obs["validUntil"] is None
            assert obs["currency"] == "EUR" and obs["source"]["origin"] == "local_user_confirmed_receipt"
            assert obs["priceCents"] == int(row["unitCents"]), row["case"]
            assert obs["egProductdaten"]["linePaidCents"] == int(row["paidCents"]), row["case"]
            assert obs["scope"]["storeId"] == (None if row["storeId"] == "-" else row["storeId"])
            assert obs["egProductdaten"]["notCurrentRetailPrice"] is True
            seen[row["case"]] = obs["observationId"]
    assert seen["base"] == seen["zero-paid"] == seen["no-store"] # identity independent of price/scope
    assert seen["base"] != seen["same-sku-other-line"]
    print(f"PASS pinned original EG-Produktdaten historical price: {len(cases)}/{len(cases)} vectors")

if __name__ == "__main__":
    main(*sys.argv[1:])
