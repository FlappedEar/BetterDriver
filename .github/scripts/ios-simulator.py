"""Prints udid= and name= of an available simulator of the newest iOS runtime on this runner.

Usage: ios-simulator.py iPad|iPhone [--private]
(names change with Xcode, so the first one whose name starts with the argument). Used by
ios.yml's build and test jobs.

--private (self-hosted runners, FBBD-434): several runner instances share one Mac, and two jobs
must never drive the same simulator. Creates a fresh simulator of the same device type named
"BD <runner name> <kind>" (deleting a leftover one of that name first) and prints its udid.
ios.yml deletes it at the end of the job.
"""
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
    udid = subprocess.check_output(
        ["xcrun", "simctl", "create", name, device["deviceTypeIdentifier"], ios[-1]], text=True
    ).strip()
    print(f"udid={udid}")
    print(f"name={name} ({ios[-1]})")
else:
    print(f"udid={device['udid']}")
    print(f"name={device['name']} ({ios[-1]})")
