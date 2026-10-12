"""Finds crashes and ANRs in the emulator's logcat (emulator-smoke.sh).

Prints one annotation per problem, readable from the check run without the log: ::error for any
process of the app (UI, recorder, camera, coach), ::warning for other processes, whose "isn't
responding" dialogs can take the window focus from the app and fail the tests that need it.
Exits 1 when the app crashed or stopped responding.
"""
import re
import sys

APP = "com.flappedear.betterdriver"
CONTEXT_LINES = 4
LINE_CHARS = 300


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def message(line):
    # threadtime: date time pid tid level tag: message
    parts = line.split(": ", 1)
    return (parts[1] if len(parts) == 2 else line).strip()[:LINE_CHARS]


def thread(line):
    # The pid and tid columns, so the lines that follow are the same report's.
    return line.split()[2:4]


def follow(lines, i, count):
    same = [line for line in lines[i + 1:i + 1 + count] if line.strip() and thread(line) == thread(lines[i])]
    return [message(line) for line in same]


lines = open(sys.argv[1], errors="replace").read().splitlines()
app_problems = 0
for i, line in enumerate(lines):
    text = message(line)
    process = None
    kind = None
    detail = []
    if "FATAL EXCEPTION" in text:
        after = follow(lines, i, CONTEXT_LINES)
        named = next((m for m in after if m.startswith("Process: ")), "")
        process = named.removeprefix("Process: ").split(",")[0]
        kind = "crashed"
        detail = [m for m in after if not m.startswith("Process: ")][:2]
    elif m := re.match(r"ANR in (\S+)", text):
        process = m.group(1)
        kind = "stopped responding (ANR)"
        detail = follow(lines, i, CONTEXT_LINES)
    elif m := re.search(r">>> (\S+) <<<", text):
        process = m.group(1)
        kind = "crashed in native code"
        detail = [text]
    if process is None:
        continue
    ours = process == APP or process.startswith(APP + ":")
    app_problems += ours
    level = "error" if ours else "warning"
    summary = "\n".join([f"{process} {kind}"] + detail)
    print(f"::{level} title={'App ' if ours else ''}{kind}::{escape(summary)}")
sys.exit(1 if app_problems else 0)
