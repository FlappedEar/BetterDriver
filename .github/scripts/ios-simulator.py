"""Prints udid= and name= of an available simulator of the newest iOS runtime on this runner.

Usage: ios-simulator.py iPad|iPhone (names change with Xcode, so the first one whose name
starts with the argument). Used by ios.yml's build and test jobs.
"""
import json
import subprocess
import sys

kind = sys.argv[1]
runtimes = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]
ios = sorted((k for k in runtimes if "iOS" in k), key=lambda k: [int(p) for p in k.rsplit("iOS-", 1)[1].split("-")])
device = next(d for d in runtimes[ios[-1]] if d["name"].startswith(kind))
print(f"udid={device['udid']}")
print(f"name={device['name']} ({ios[-1]})")
