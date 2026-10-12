"""Reports each failed test of a JUnit XML report (emulator-smoke.sh).

Each failure becomes an ::error annotation, readable from the check run without the log: the test,
the start of its message, the app's own frames and the first cause. The whole stack trace follows
in a log group.
"""
import sys
import xml.etree.ElementTree as ET

MESSAGE_LINES = 2
APP_FRAMES = 4
LINE_CHARS = 300


def escape_data(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def summary(trace):
    lines = [line.strip() for line in trace.splitlines() if line.strip()]
    message = [line for line in lines if not line.startswith(("at ", "Caused by:"))][:MESSAGE_LINES]
    frames = [line for line in lines if line.startswith("at com.flappedear.")][:APP_FRAMES]
    cause = [line for line in lines if line.startswith("Caused by:")][:1]
    return "\n".join(line[:LINE_CHARS] for line in message + frames + cause)


for case in ET.parse(sys.argv[1]).getroot().iter("testcase"):
    for failure in list(case.iter("failure")) + list(case.iter("error")):
        name = f"{case.get('classname')}.{case.get('name')}"
        trace = (failure.text or failure.get("message") or "").strip()
        print(f"::error title={escape_property('Failed: ' + name)}::{escape_data(summary(trace))}")
        print(f"::group::{name}")
        print(trace)
        print("::endgroup::")
