#!/usr/bin/env python3
"""App Store Connect API helper for ios-release.yml (FBBD-334).

    asc.py team BUNDLE_ID NAME   registers BUNDLE_ID if missing, prints the team id (seedId)
    asc.py ci-certs              revokes the development certificates earlier runs made
    asc.py capability BUNDLE_ID TYPE
                                 turns capability TYPE (such as HOT_SPOT) on for BUNDLE_ID if it
                                 is off; exits 1 when it cannot, so the caller can sign without it

Each signed archive on a fresh runner makes a new development certificate through the
API key ("Created via API") and loses its private key with the runner. They pile up to
the account's limit, after which archiving fails ("Choose a certificate to revoke").
Distribution signing on export is cloud managed and makes none.

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
            data = r.read()
            return json.loads(data) if data else None
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


def ci_certs():
    q = urllib.parse.urlencode({"filter[certificateType]": "DEVELOPMENT,IOS_DEVELOPMENT", "limit": 200})
    for c in call("GET", f"/certificates?{q}")["data"]:
        a = c["attributes"]
        label = f'{a.get("certificateType")} {a.get("name")!r} {a.get("displayName")!r} until {a.get("expirationDate")}'
        if "Created via API" in f'{a.get("name")} {a.get("displayName")}':
            call("DELETE", f'/certificates/{c["id"]}')
            print(f"revoked {label}")
        else:
            print(f"kept {label}")


def capability(bundle_id, kind):
    """Turns capability [kind] on for [bundle_id]; prints what it did. Hotspot Configuration (HOT_SPOT)
    lets the app join a GoPro's Wi-Fi (FBBD-343)."""
    q = urllib.parse.urlencode({"filter[identifier]": bundle_id})
    found = [b for b in call("GET", f"/bundleIds?{q}")["data"] if b["attributes"]["identifier"] == bundle_id]
    if not found:
        sys.exit(f"no bundle id {bundle_id}")
    bid = found[0]["id"]
    have = call("GET", f"/bundleIds/{bid}/bundleIdCapabilities")["data"]
    if any(c["attributes"].get("capabilityType") == kind for c in have):
        print(f"{bundle_id} has {kind}")
        return
    body = {"data": {
        "type": "bundleIdCapabilities",
        "attributes": {"capabilityType": kind},
        "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bid}}},
    }}
    call("POST", "/bundleIdCapabilities", body)
    print(f"turned {kind} on for {bundle_id}")


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "team":
        team(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 4 and sys.argv[1] == "capability":
        capability(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 2 and sys.argv[1] == "ci-certs":
        ci_certs()
    else:
        sys.exit(__doc__)
