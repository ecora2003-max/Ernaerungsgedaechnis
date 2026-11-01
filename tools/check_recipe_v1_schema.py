#!/usr/bin/env python3
"""Pinned v1 JSON-Schema Draft 2020-12 contract regression for host recipe fixture.

Schema validation is a CI evidence gate, not an Android runtime validator.
No user data or external API is accessed by this script.
"""
import copy
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


def main(schema_path: str, fixture_path: str) -> None:
    schema = json.loads(Path(schema_path).read_text(encoding="utf-8"))
    fixture = json.loads(Path(fixture_path).read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    checker = FormatChecker()
    if "date-time" not in checker.checkers:
        raise RuntimeError("date-time format assertion unavailable; install jsonschema[format]")
    validator = Draft202012Validator(schema, format_checker=checker)

    def valid(obj: dict, label: str) -> None:
        failures = list(validator.iter_errors(obj))
        if failures:
            raise AssertionError(label + ": " + failures[0].message)

    def invalid(mutator, label: str) -> None:
        obj = copy.deepcopy(fixture)
        mutator(obj)
        if validator.is_valid(obj):
            raise AssertionError("invalid case accepted: " + label)

    valid(fixture, "host recipe fixture")
    for bits in range(8):
        obj = copy.deepcopy(fixture)
        obj["payload"]["flags"] = {
            "considerStock": bool(bits & 1),
            "considerShopping": bool(bits & 2),
            "considerOffers": bool(bits & 4),
        }
        valid(obj, "context flags " + str(bits))

    invalid(lambda x: x.update(contractVersion="1.1.0-draft"), "draft version")
    invalid(lambda x: x.update(messageType="receipt.line"), "wrong message type")
    invalid(lambda x: x.pop("meta"), "missing metadata")
    invalid(lambda x: x["meta"].update(extra=True), "forbidden meta extension")
    invalid(lambda x: x["meta"].update(createdAt="yesterday"), "invalid offset time")
    invalid(lambda x: x["payload"].update(status="CONFIRMED"), "confirmed status")
    invalid(lambda x: x["payload"].update(portionCount=0), "zero portions")
    invalid(lambda x: x["payload"].update(ingredients=[]), "no ingredients")
    invalid(lambda x: x["payload"]["flags"].pop("considerOffers"), "missing consent")
    invalid(lambda x: x["payload"]["flags"].update(considerStock="false"), "string consent")
    invalid(lambda x: x["payload"]["ingredients"][0]["quantity"].update(amount=-1), "negative amount")
    invalid(lambda x: x["payload"]["ingredients"][0]["quantity"].update(amount=1.5), "fractional amount")
    invalid(lambda x: x["payload"]["ingredients"][0]["quantity"].update(confirmed="true"), "forged boolean type")
    invalid(lambda x: x["payload"].update(warnings=[7]), "non-string warning")
    print("RECIPE_V1_SCHEMA_PASS: 9 positive / 14 negative pinned schema cases")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: check_recipe_v1_schema.py schema.json fixture.json")
    main(sys.argv[1], sys.argv[2])
