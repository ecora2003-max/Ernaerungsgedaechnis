#!/usr/bin/env python3
"""Pinned, pure EG-Preisdaten parsers vs the SAME synthetic Kotlin/device inputs.

No retailer I/O or source activation. Source bytes are checked before import;
only the pinned pure parsers are loaded, never a live-probe script. Host deliberately
requires ID/title and EUR; JSON totals never establish coverage. Netto's link
time is retained separately, not asserted as the offer's actual expiry.
"""
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import sys


def load(path, name, pin):
    content = path.read_bytes()
    assert hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest() == pin
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def main(stationary_path, netto_path, offers_path, fixture_path):
    stationary = load(Path(stationary_path), "eg_pinned_stationary", "ff44bc694bc875c82ca5d60c2b6b9fb99026ec8d")
    netto = load(Path(netto_path), "eg_pinned_netto", "ef69a4f624c6608cd65b0157d75af2214bae14b5")
    offers = load(Path(offers_path), "eg_pinned_embedded_offers", "a1ede148148864f958d802686a0fd5026f64fe5f")
    directory = Path(fixture_path)
    expected = json.loads((directory / "reference_expected.json").read_text())
    actual = {}
    for kind, parser, store in [("rewe", stationary.rewe_stationary, "000001"),
                                ("aldi_sued", stationary.aldi_sued, "B000")]:
        records = parser(json.loads((directory / (kind + ".json")).read_text()), store)["listings"]
        # Explicit host acceptance, not a source parser pretending to grant
        # currency/identity/display rights. Missing/fractional prices stay absent.
        records = [r for r in records if r["currency"] == "EUR" and r["product_id"] and r["title"]]
        actual[kind] = [{"id": r["product_id"], "current": r["price_cents"],
                         "regular": r["regular_cents"], "deposit": r["deposit_cents"],
                         "previous": r["previous_price_display"], "brand": r["brand"]} for r in records]
    records, metadata = netto.parse_branch_offers((directory / "netto.html").read_text(),
                                                "https://www.netto-online.de/angebote/c-N07")
    assert metadata["candidate_tiles"] == metadata["verified_sale_price_tiles"] == 2
    def cents(value):
        if value is None:
            return None
        result = Decimal(value) * 100
        assert result == result.to_integral_value()
        return int(result)
    actual["netto"] = [{"id": r.offer_id, "current": cents(r.sale_price_eur),
                        "app": cents(r.advertised_app_price_eur), "wishlist": r.valid_to_local,
                        "from": r.valid_from_date, "unitText": r.advertised_unit_price} for r in records]
    keys = ("id", "current", "regular", "deposit", "previous", "app", "brand", "wishlist", "from", "unitText")
    def canonical(groups):
        return {name: [{key: row.get(key) for key in keys} for row in rows] for name, rows in groups.items()}
    assert canonical(actual) == canonical(expected), (actual, expected)
    assert sum(len(rows) for rows in actual.values()) == 10
    embedded_expected = json.loads((directory / "embedded_reference_expected.json").read_text())
    embedded = {
        "aldi_nord": offers.aldi_nord_next((directory / "aldi_nord.html").read_text()),
        "kaufland": offers.kaufland_offers((directory / "kaufland.html").read_text(),
            json.loads((directory / "kaufland_availability.json").read_text()), store_id="DE1234",
            first_day="2026-11-09", last_day="2026-11-14")}
    embedded_actual = {}
    for name, rows in embedded.items():
        assert len(rows) == 1
        row = rows[0]
        embedded_actual[name] = {"id": row.product_id, "current": cents(row.price_eur), "quantityText": row.quantity,
                                 "from": row.valid_from, "until": row.valid_to,
                                 "scope": "CHAIN_REPORTED" if row.scope.value == "CHAIN_WIDE_REPORTED" else row.scope.value,
                                 "store": row.scope_id}
    assert embedded_actual == embedded_expected, (embedded_actual, embedded_expected)
    print("PUBLISHED_SOURCE_REFERENCE_PASS: 12 synthetic price rows including embedded ALDI-Nord/Kaufland; three original Git-blob pins; no live I/O")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: check_published_source_reference.py stationary.py netto.py retailer-offers.py fixture-directory")
    main(*sys.argv[1:])
