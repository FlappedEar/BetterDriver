#!/usr/bin/env python3
"""Did ios.yml's full suite already pass on an app identical to HEAD's? (FBBD-457)

ios.yml's last job is named "iOS passed (tree X)". This looks for such a successful job on the
recent main commits and on the heads of the pull requests merged into them, newest first. A
candidate counts when every file that differs between it and HEAD is outside ios.yml's path
filter (the files that cannot change the iOS app or its tests), except a gradle.properties change
that only moves betterdriver.version. Examples: the squash of a PR whose base lacked a docs or
workflow commit, or a release PR that only bumps the version.

Prints the matching job's URL and exits 0, or prints why not and exits 1.
Usage: ios-tested.py [depth]   (needs GH_TOKEN and a full clone)
"""
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

REPO = os.environ["GITHUB_REPOSITORY"]
IOS_YML = ".github/workflows/ios.yml"


def run(*args, check=True):
    return subprocess.run(args, capture_output=True, text=True, check=check).stdout


def gh(path):
    """GET from the GitHub API with GH_TOKEN. Plain urllib: self-hosted Macs may lack the gh CLI
    (TestFlight 1551 on mbp: "gh: command not found", so the gate ran every UI test)."""
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    req = urllib.request.Request(f"{api}/{path}", headers={
        "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as e:
        print(f"GitHub API {path}: {e}", file=sys.stderr)
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


def passed_jobs(commit):
    """(tested tree, job URL) of each successful "iOS passed (tree X)" job on the commit."""
    data = gh(f"repos/{REPO}/commits/{commit}/check-runs?per_page=100")
    out = []
    for c in (data or {}).get("check_runs", []):
        m = re.match(r"^iOS passed \(tree ([0-9a-f]{40})\)$", c["name"])
        if m and c["conclusion"] == "success":
            out.append((m.group(1), c["html_url"]))
    return out


def present(obj, kind):
    return run("git", "cat-file", "-t", obj, check=False).strip() == kind


def main():
    depth = int(sys.argv[1]) if len(sys.argv) > 1 else 15
    paths = ios_paths()
    head_tree = run("git", "rev-parse", "HEAD^{tree}").strip()
    for commit in run("git", "rev-list", "--first-parent", f"--max-count={depth}", "HEAD").split():
        pulls = gh(f"repos/{REPO}/commits/{commit}/pulls") or []
        heads = [p["head"]["sha"] for p in pulls if p.get("merged_at")]
        for candidate in [commit] + heads:
            for tree, url in passed_jobs(candidate):
                if tree == head_tree:
                    print(f"iOS CI passed on this exact tree: {url}")
                    return 0
                # A pull request's run tests its merge with main at that time and names that
                # tree; compare with it when it is here, else with the head commit, which can only
                # differ more (main's newer files count as differences).
                if present(tree, "tree"):
                    against = tree
                else:
                    if not present(candidate, "commit"):
                        subprocess.run(["git", "fetch", "-q", "origin", candidate], check=False)
                    if not present(candidate, "commit"):
                        print(f"{candidate[:7]}: iOS passed ({url}) but the commit could not be fetched")
                        continue
                    against = candidate
                hits = relevant(against, paths)
                if not hits:
                    print(f"iOS CI passed on {candidate[:7]}, which differs from HEAD only outside the app: {url}")
                    return 0
                # History is not monotonic (a revert can bring an older pass back), so keep looking.
                print(f"{candidate[:7]}: iOS passed ({url}) but HEAD differs in {', '.join(hits[:5])}"
                      + (f" and {len(hits) - 5} more" if len(hits) > 5 else ""))
    print(f"No usable iOS CI pass in the last {depth} main commits or their pull requests")
    return 1


if __name__ == "__main__":
    sys.exit(main())
