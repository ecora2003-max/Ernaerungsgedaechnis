#!/usr/bin/env python3
"""Compare checked-in Kotlin golden vectors with the *actual pinned* EG-Produktdaten reference.

No private files, retail HTTP endpoints, Sheet data or app DB. The caller fetches only
core.py from the pinned public GitHub commit; module is loaded without installing deps.
"""
import importlib.util
import sys
from pathlib import Path

def verify(ref: Path, fixture: Path):
    spec = importlib.util.spec_from_file_location("eg_productdaten_pinned_reference", ref)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    count = 0
    for line in fixture.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) != 8:
            raise AssertionError(f"Invalid fixture row {line!r}")
        name, raw, kind, raw_amount, confirmed, expected_kind, expected_amount, warnings = parts
        if confirmed not in ("true", "false"):
            raise AssertionError(f"Invalid fixture boolean: {name}")
        proposal = module.classify_receipt_line({
            "rawText": raw, "lineKind": kind,
            "amountCents": None if raw_amount == "null" else int(raw_amount),
            "confirmed": confirmed == "true",
        })
        expected = (
            expected_kind,
            None if expected_amount == "null" else int(expected_amount),
            [] if warnings == "-" else warnings.split(","),
            confirmed == "true",
        )
        actual = (proposal["lineKind"], proposal["amountCents"],
                  proposal["egProductdaten"]["warnings"], proposal["confirmed"])
        if actual != expected or proposal["rawText"] != raw:
            raise AssertionError(f"{name}: expected {expected!r}, actual {actual!r}")
        count += 1
    if count < 15:
        raise AssertionError("Reference parity fixture unexpectedly incomplete")
    print(f"PASS pinned original EG-Produktdaten receipt reference: {count}/{count} vectors")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: check_product_receipt_reference.py core.py synthetic_vectors.tsv")
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
