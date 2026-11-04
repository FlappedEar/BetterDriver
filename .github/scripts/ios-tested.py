#!/usr/bin/env python3
"""Did ios.yml's full suite already pass on an app identical to HEAD's? (FBBD-457)

ios.yml's last job is named "iOS passed (tree X)". This looks for such a successful job on the
recent main commits and on the heads of the pull requests merged into them, newest first. A
candidate counts when every file that differs between it and HEAD is outside ios.yml's path
filter (the files that cannot change the iOS app or its tests), except a gradle.properties change
that only moves betterdriver.version. Examples: the squash of a PR whose base lacked a docs or
workflow commit, or a release PR that only bumps the version.

Prints the matching job's URL and exits 0, or prints why not and exits 1.
Usage: ios-tested.py [depth]   (needs gh with GH_TOKEN and a full clone)
"""
import json
import os
import re
import subprocess
import sys

REPO = os.environ["GITHUB_REPOSITORY"]
IOS_YML = ".github/workflows/ios.yml"


def run(*args, check=True):
    return subprocess.run(args, capture_output=True, text=True, check=check).stdout


def gh(path):
    try:
        return json.loads(run("gh", "api", "-X", "GET", path))
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        return None


def ios_paths():
    """The glob list under on.pull_request.paths of ios.yml, as regexes."""
    lines = open(IOS_YML).read().splitlines()
    globs, inside = [], False
    for line in lines:
        if line.startswith("  pull_request:"):
            inside = True
            continue
        if inside and re.match(r"^  \S", line):
            break
        m = re.match(r'^\s+- "(.+)"$', line)
        if inside and m:
            globs.append(m.group(1))
    if not globs:
        sys.exit("no paths read from " + IOS_YML)
    out = []
    for g in globs:
        rx = re.escape(g).replace(r"\*\*", "\0").replace(r"\*", "[^/]*").replace("\0", ".*")
        out.append(re.compile("^" + rx + "$"))
    return out


def version_only(commit):
    diff = run("git", "diff", "-U0", commit, "HEAD", "--", "gradle.properties")
    changed = [l for l in diff.splitlines() if l[:1] in "+-" and not l.startswith(("+++", "---"))]
    return all(re.match(r"^[+-]betterdriver\.version=", l) for l in changed)


def relevant(commit, paths):
    files = run("git", "diff", "--name-only", commit, "HEAD").split()
    hits = [f for f in files if any(p.match(f) for p in paths)]
    if hits == ["gradle.properties"] and version_only(commit):
        return []
    return hits


def passed_job(commit):
    data = gh(f"repos/{REPO}/commits/{commit}/check-runs?per_page=100")
    for c in (data or {}).get("check_runs", []):
        if c["name"].startswith("iOS passed (tree ") and c["conclusion"] == "success":
            return c["html_url"]
    return None


def main():
    depth = int(sys.argv[1]) if len(sys.argv) > 1 else 15
    paths = ios_paths()
    for commit in run("git", "rev-list", "--first-parent", f"--max-count={depth}", "HEAD").split():
        pulls = gh(f"repos/{REPO}/commits/{commit}/pulls") or []
        heads = [p["head"]["sha"] for p in pulls if p.get("merged_at")]
        for candidate in [commit] + heads:
            url = passed_job(candidate)
            if not url:
                continue
            if run("git", "cat-file", "-t", candidate, check=False).strip() != "commit":
                subprocess.run(["git", "fetch", "-q", "origin", candidate], check=False)
            if run("git", "cat-file", "-t", candidate, check=False).strip() != "commit":
                print(f"{candidate[:7]}: iOS passed ({url}) but the commit could not be fetched")
                continue
            hits = relevant(candidate, paths)
            if not hits:
                print(f"iOS CI passed on {candidate[:7]}, which differs from HEAD only outside the app: {url}")
                return 0
            print(f"{candidate[:7]}: iOS passed ({url}) but HEAD differs in {', '.join(hits[:5])}"
                  + (f" and {len(hits) - 5} more" if len(hits) > 5 else ""))
            # An older pass can only differ more: stop at the newest one.
            return 1
    print(f"No iOS CI pass in the last {depth} main commits or their pull requests")
    return 1


if __name__ == "__main__":
    sys.exit(main())
