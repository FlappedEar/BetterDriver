"""Where the UI tests' time goes (FBBD-457), from xcodebuild's test log.

Usage: ios-test-profile.py build/xcodebuild.log

XCTest logs each step of a UI test as "t = <seconds>s <step>". A step lasts until the next one
starts. Prints, per test, the steps of 5 s or more, and the total per kind of step (its first
three words, e.g. "Launch", "Wait for", "Find the"), so a slow launch, fixture or wait shows in
the job log without downloading the result bundle.
"""
import collections
import re
import sys

case = re.compile(r"Test Case '-\[(\S+) (\S+)\]' (started|passed|failed)")
step = re.compile(r"^\s+t =\s+([0-9.]+)s (.*)$")

per_kind = collections.defaultdict(float)
lines = []
test = None
steps = []


def close(test, steps, end):
    for (t, name), (t_next, _) in zip(steps, steps[1:] + [(end, "")]):
        seconds = t_next - t
        words = re.sub(r'"[^"]*"|\'[^\']*\'', "", name).split()
        kind = " ".join([w for w in words if not re.match(r"^[0-9.]+s?$", w)][:3])
        per_kind[kind] += seconds
        if seconds >= 5:
            lines.append(f"PROFILE {test} {seconds:6.1f}s {name[:140]}")


with open(sys.argv[1], errors="replace") as log:
    for line in log:
        m = case.search(line)
        if m:
            if m.group(3) == "started":
                test, steps = f"{m.group(1).split('.')[-1]}.{m.group(2)}", []
            elif test and steps:
                # "passed (12.345 seconds)": the test's end on the steps' clock.
                d = re.search(r"\(([0-9.]+) seconds\)", line)
                close(test, steps, float(d.group(1)) if d else steps[-1][0])
                test = None
            continue
        m = step.match(line)
        if m and test:
            steps.append((float(m.group(1)), m.group(2).strip()))

print("\n".join(lines))
for kind, seconds in sorted(per_kind.items(), key=lambda kv: -kv[1])[:25]:
    print(f"PROFILE TOTAL {seconds:7.1f}s {kind}")
