#!/usr/bin/env python3
"""Fail-closed audit of this exact CI run's JUnit XML, not cached reports.

No network, user data, or Android device. This is a report verifier, not a test
substitute; instrumented Android tests remain a separate acceptance gate.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

if len(sys.argv) != 2:
    raise SystemExit("usage: check_junit_xml.py <test-results-directory>")
directory = Path(sys.argv[1])
files = sorted(directory.glob("TEST-*.xml"))
if not files:
    raise SystemExit("FAIL: no current JUnit XML reports found")

counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
migration_tests = 0
for file in files:
    root = ET.parse(file).getroot()
    if root.tag != "testsuite":
        raise SystemExit(f"FAIL: unexpected JUnit root in {file.name}: {root.tag}")
    for key in counts:
        value = int(root.attrib.get(key, "0"))
        if value < 0:
            raise SystemExit(f"FAIL: negative {key} in {file.name}")
        counts[key] += value
    cases = len(root.findall("testcase"))
    if cases != int(root.attrib.get("tests", "-1")):
        raise SystemExit(f"FAIL: testcase count mismatch in {file.name}")
    if root.attrib.get("name", "").endswith(".DatabaseMigrationTest"):
        migration_tests += cases

print(f"JUNIT_XML: files={len(files)} tests={counts['tests']} "
      f"failures={counts['failures']} errors={counts['errors']} "
      f"skipped={counts['skipped']} migrations={migration_tests}")
if counts["tests"] < 239:
    raise SystemExit("FAIL: unexpected regression in host JVM test count (<239)")
if migration_tests < 7:
    raise SystemExit("FAIL: missing synthetic 1..7 -> Room 8 migration tests")
if any(counts[key] for key in ("failures", "errors", "skipped")):
    raise SystemExit("FAIL: JVM tests failed, errored, or were skipped")
print("JUNIT_XML_PASS: fresh host JVM and Room migration evidence")
