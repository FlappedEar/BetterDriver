"""Prints udid= and name= of an available simulator of the newest iOS runtime on this runner.

Usage: ios-simulator.py iPad|iPhone [--private]
(names change with Xcode, so the first one whose name starts with the argument). Used by
ios.yml's build and test jobs.

--private (self-hosted runners, FBBD-434/457): several runner instances share one Mac, and two jobs
must never drive the same simulator. Each runner keeps its own simulator per device and runtime,
"BD <runner name> <kind> <runtime>", made once and reused by every later job: a new simulator spends
its first minutes making wallpapers and widgets (KaleidoscopePoster, PhotosPosterProvider, widget
extensions near 100% CPU each, seen on the Mac mini). The job shuts down this runner's other
simulators (so each runner has one booted), boots this one, and removes the app with its data and
the keychain, so every job still starts as a first install.
"""
import json
import os
import subprocess
import sys
import time

kind = sys.argv[1]
private = "--private" in sys.argv[2:]


def devices():
    return json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "-j"]))["devices"]


runtimes = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]
ios = sorted((k for k in runtimes if "iOS" in k), key=lambda k: [int(p) for p in k.rsplit("iOS-", 1)[1].split("-")])
device = next(d for d in runtimes[ios[-1]] if d["name"].startswith(kind) and not d["name"].startswith("BD "))

if private:
    runner = os.environ.get("RUNNER_NAME", "local")
    name = f"BD {runner} {kind} {ios[-1].rsplit('.', 1)[-1]}"
    for d in [d for ds in devices().values() for d in ds if d["name"].startswith(f"BD {runner} ")]:
        if d["name"] == name:
            continue
        if d["state"] != "Shutdown":
            subprocess.run(["xcrun", "simctl", "shutdown", d["udid"]], capture_output=True)
        # Same device from an older runtime, the per-job simulators of before FBBD-457, broken ones.
        if d["name"].startswith(f"BD {runner} {kind}") or not d.get("isAvailable", True):
            subprocess.run(["xcrun", "simctl", "delete", d["udid"]], capture_output=True)
    found = [d for d in devices().get(ios[-1], []) if d["name"] == name]
    start = time.time()
    made = not found
    if made:
        udid = subprocess.check_output(
            ["xcrun", "simctl", "create", name, device["deviceTypeIdentifier"], ios[-1]], text=True
        ).strip()
    else:
        udid = found[0]["udid"]
    subprocess.run(["xcrun", "simctl", "bootstatus", udid, "-b"], check=True, capture_output=True)
    if made:
        # Lets the first boot's wallpaper and widget work finish before the first job's tests.
        time.sleep(90)
    for app in ["com.flappedear.betterdriver", "com.flappedear.betterdriver.uitests.xctrunner",
                "com.flappedear.betterdriver.tests"]:
        subprocess.run(["xcrun", "simctl", "uninstall", udid, app], capture_output=True)
    subprocess.run(["xcrun", "simctl", "keychain", udid, "reset"], capture_output=True)
    print(f"{'Made' if made else 'Reused'} {name}, ready in {time.time() - start:.0f} s", file=sys.stderr)
    print(f"udid={udid}")
    print(f"name={name}")
else:
    print(f"udid={device['udid']}")
    print(f"name={device['name']} ({ios[-1]})")
