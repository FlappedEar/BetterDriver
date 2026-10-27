#!/usr/bin/env python3
"""App Store Connect API helper for ios-release.yml (FBBD-334).

    asc.py team BUNDLE_ID NAME   registers BUNDLE_ID if missing, prints the team id (seedId)

Reads ASC_ISSUER_ID, ASC_KEY_ID and ASC_KEY_PATH (the .p8 file) from the environment.
Needs PyJWT and cryptography.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

API = "https://api.appstoreconnect.apple.com/v1"


def token():
    with open(os.environ["ASC_KEY_PATH"]) as f:
        key = f.read()
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"},
        key,
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"},
    )


def call(method, path, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body else None)
    req.add_header("Authorization", "Bearer " + token())
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path}: HTTP {e.code} {e.read().decode()}")


def team(bundle_id, name):
    q = urllib.parse.urlencode({"filter[identifier]": bundle_id})
    found = [b for b in call("GET", f"/bundleIds?{q}")["data"] if b["attributes"]["identifier"] == bundle_id]
    if not found:
        body = {"data": {"type": "bundleIds", "attributes": {"identifier": bundle_id, "name": name, "platform": "IOS"}}}
        found = [call("POST", "/bundleIds", body)["data"]]
        print(f"registered bundle id {bundle_id}", file=sys.stderr)
    print(found[0]["attributes"]["seedId"])


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "team":
        team(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
