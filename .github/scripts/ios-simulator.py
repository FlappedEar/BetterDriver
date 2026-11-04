"""Prints udid= and name= of an available simulator of the newest iOS runtime on this runner.

Usage: ios-simulator.py iPad|iPhone [--private]
(names change with Xcode, so the first one whose name starts with the argument). Used by
ios.yml's build and test jobs.

--private (self-hosted runners, FBBD-434): several runner instances share one Mac, and two jobs
must never drive the same simulator. Creates a fresh simulator of the same device type named
"BD <runner name> <kind>" (deleting a leftover one of that name first) and prints its udid.
ios.yml deletes it at the end of the job.

The new simulator is a clone of a template ("BD template <kind> <runtime>") that has booted once
and been shut down (FBBD-457): a never-booted simulator spends its first minutes making wallpapers
and widgets (KaleidoscopePoster, PhotosPosterProvider, widget extensions near 100% CPU each on the
Mac mini) while the tests run. The template is made once per runtime, under a lock, by whichever
job needs it first.
"""
import fcntl
import time
import json
import os
import subprocess
import sys

kind = sys.argv[1]
private = "--private" in sys.argv[2:]


def devices():
    return json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "-j"]))["devices"]


runtimes = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]
ios = sorted((k for k in runtimes if "iOS" in k), key=lambda k: [int(p) for p in k.rsplit("iOS-", 1)[1].split("-")])
device = next(d for d in runtimes[ios[-1]] if d["name"].startswith(kind) and not d["name"].startswith("BD "))

if private:
    name = f"BD {os.environ.get('RUNNER_NAME', 'local')} {kind}"
    for runtime_devices in devices().values():
        for d in runtime_devices:
            if d["name"] == name:
                subprocess.run(["xcrun", "simctl", "shutdown", d["udid"]], capture_output=True)
                subprocess.run(["xcrun", "simctl", "delete", d["udid"]], check=True)
    template = f"BD template {kind} {ios[-1].rsplit('.', 1)[-1]}"
    with open(f"/tmp/bd-sim-template-{kind}.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        found = [d for d in devices().get(ios[-1], []) if d["name"] == template and d.get("isAvailable", True)]
        if found:
            source = found[0]["udid"]
        else:
            start = time.time()
            source = subprocess.check_output(
                ["xcrun", "simctl", "create", template, device["deviceTypeIdentifier"], ios[-1]], text=True
            ).strip()
            subprocess.run(["xcrun", "simctl", "bootstatus", source, "-b"], check=True, capture_output=True)
            # Lets the first boot's wallpaper and widget work finish before the template is frozen.
            time.sleep(120)
            subprocess.run(["xcrun", "simctl", "shutdown", source], check=True)
            print(f"Made {template} in {time.time() - start:.0f} s", file=sys.stderr)
        udid = subprocess.check_output(["xcrun", "simctl", "clone", source, name], text=True).strip()
    print(f"udid={udid}")
    print(f"name={name} ({ios[-1]})")
else:
    print(f"udid={device['udid']}")
    print(f"name={device['name']} ({ios[-1]})")
