"""Self-test of logcat-problems.py (review v1.34.1 F17): the same app crash must fail with and
without unrelated log lines between FATAL EXCEPTION and its Process: line. Run by ci.yml."""
import os
import subprocess
import sys
import tempfile

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logcat-problems.py")
APP = "com.flappedear.betterdriver"


def run(lines):
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as f:
        f.write("\n".join(lines) + "\n")
    try:
        return subprocess.run([sys.executable, SCRIPT, f.name], capture_output=True, text=True)
    finally:
        os.unlink(f.name)


def crash(noise, with_process=True):
    head = "11-20 10:00:00.000  4321  4400 E AndroidRuntime: FATAL EXCEPTION: recorder-writer"
    rest = []
    if with_process:
        rest.append(f"11-20 10:00:00.001  4321  4400 E AndroidRuntime: Process: {APP}:recorder, PID: 4321")
    rest.append("11-20 10:00:00.002  4321  4400 E AndroidRuntime: java.lang.IllegalStateException: boom")
    filler = [f"11-20 10:00:00.00{k}   555   600 D SurfaceFlinger: composite frame {k}" for k in range(noise)]
    start = f"11-20 09:59:59.000   900   950 I ActivityManager: Start proc 4321:{APP}:recorder/u0a190 for service"
    return [start, head] + filler + rest


failures = []
for noise in (0, 4, 50):
    result = run(crash(noise))
    if result.returncode != 1 or "::error" not in result.stdout:
        failures.append(f"crash with {noise} unrelated lines passed: {result.stdout!r}")
result = run(crash(4, with_process=False))
if result.returncode != 1:
    failures.append(f"crash without a Process: line passed: {result.stdout!r}")
other = [line.replace(APP, "com.android.systemui") for line in crash(4)]
if run(other).returncode != 0:
    failures.append("another app's crash failed the job")
if run(["11-20 10:00:00.000   555   600 D SurfaceFlinger: nothing"]).returncode != 0:
    failures.append("a clean log failed the job")
for f in failures:
    print(f"::error::{f}")
print("logcat-problems self-test:", "FAIL" if failures else "ok")
sys.exit(1 if failures else 0)
