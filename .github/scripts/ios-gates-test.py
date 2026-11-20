"""Self-test of the iOS release gate and path filters (review v1.34.1 F10, F16). Run by ci.yml.

- F10: ios-tested.py counts only "iOS full suite passed (tree X)" marks that succeeded, never the
  "iOS passed (tree X)" of a pull request run, which covers only part of the iPad's tests.
- F16: every file XcodeGen copies into the app from outside ios/ (ios/project.yml "path: ../...")
  is in both of ios.yml's path filters, so changing it runs iOS CI and counts at release.
"""
import importlib.util
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
os.chdir(ROOT)
spec = importlib.util.spec_from_file_location("ios_tested", os.path.join(HERE, "ios-tested.py"))
tested = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tested)

failures = []
tree = "a" * 40
runs = [
    {"name": f"iOS passed (tree {tree})", "conclusion": "success", "html_url": "pr"},
    {"name": f"iOS full suite passed (tree {'b' * 40})", "conclusion": "failure", "html_url": "red"},
]
if tested.complete_passes(runs):
    failures.append("a partial pull request pass or a failed full suite counted as a full pass")
runs.append({"name": f"iOS full suite passed (tree {tree})", "conclusion": "success", "html_url": "full"})
if tested.complete_passes(runs) != [(tree, "full")]:
    failures.append(f"a full suite pass was not found: {tested.complete_passes(runs)}")


def filters():
    """The glob lists of ios.yml's pull_request and push path filters, as regexes."""
    out, current = {}, None
    for line in open(".github/workflows/ios.yml").read().splitlines():
        if m := re.match(r"^  (pull_request|push):", line):
            current = m.group(1)
            out[current] = []
            continue
        if re.match(r"^  \S", line) or line.startswith("permissions"):
            current = None
        m = re.match(r'^\s+- "(.+)"$', line)
        if current and m:
            g = m.group(1)
            rx = re.escape(g).replace(r"\*\*", "\0").replace(r"\*", "[^/]*").replace("\0", ".*")
            out[current].append(re.compile("^" + rx + "$"))
    return out


external = re.findall(r"^\s+- path: \.\./(\S+)", open("ios/project.yml").read(), re.M)
if not external:
    failures.append("no external resources found in ios/project.yml")
lists = filters()
if set(lists) != {"pull_request", "push"}:
    failures.append(f"ios.yml path filters not found: {sorted(lists)}")
for path in external:
    files = subprocess.run(["git", "ls-files", path], capture_output=True, text=True).stdout.split()
    if not files:
        failures.append(f"ios/project.yml names ../{path}, which has no tracked files")
    for f in files:
        for event, regexes in lists.items():
            if not any(r.match(f) for r in regexes):
                failures.append(f"{f} (bundled by ios/project.yml) is not in ios.yml's {event} paths")
for f in failures:
    print(f"::error::{f}")
print("iOS gate self-test:", "FAIL" if failures else f"ok ({len(external)} external resources)")
sys.exit(1 if failures else 0)
