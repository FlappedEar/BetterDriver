"""Prints each failed test of a JUnit XML report with its whole stack trace (emulator-smoke.sh)."""
import sys
import xml.etree.ElementTree as ET

for case in ET.parse(sys.argv[1]).getroot().iter("testcase"):
    for failure in list(case.iter("failure")) + list(case.iter("error")):
        print(f"::group::{case.get('classname')}.{case.get('name')}")
        print((failure.text or failure.get("message") or "").strip())
        print("::endgroup::")
