#!/usr/bin/env python3
"""Flags open pull requests that have been green for more than 30 minutes without a merge.

A finished PR must not sit unmerged because the thread that opened it never came back
after CI (#439 sat green for 46 min, #416 for a day and a half). stale-prs.yml runs this
every 15 minutes. A PR counts as green when it is not a draft, every check run on its head
commit has completed as success, skipped or neutral, and no commit status is pending or
failing. It has been green since the last of those finished.

For each PR green for longer than the limit, the script comments on the PR once per head
commit (a comment on the PR wakes the session that follows it) and lists it in the job
summary. It never merges anything itself.

Environment: GITHUB_TOKEN, GITHUB_REPOSITORY, optional STALE_MINUTES (default 30),
DRY_RUN=1 to only print.
"""
import datetime as dt
import json
import os
import sys
import urllib.request

API = "https://api.github.com"
MARKER = "<!-- stale-green-pr:{sha} -->"
OK_CONCLUSIONS = {"success", "skipped", "neutral"}


def request(path, method="GET", body=None):
    req = urllib.request.Request(
        API + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def paged(path, key=None):
    page = 1
    while True:
        sep = "&" if "?" in path else "?"
        data = request(f"{path}{sep}per_page=100&page={page}")
        items = data[key] if key else data
        yield from items
        if len(items) < 100:
            return
        page += 1


def parse_time(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def green_since(check_runs, statuses):
    """When the head commit turned green, or None while it is not green.

    check_runs: [{status, conclusion, completed_at}], statuses: [{state, updated_at}]
    (the latest status per context).
    """
    if not check_runs and not statuses:
        return None
    times = []
    for run in check_runs:
        if run["status"] != "completed" or run["conclusion"] not in OK_CONCLUSIONS:
            return None
        times.append(parse_time(run["completed_at"]))
    for status in statuses:
        if status["state"] != "success":
            return None
        times.append(parse_time(status["updated_at"]))
    return max(times)


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    limit = dt.timedelta(minutes=int(os.environ.get("STALE_MINUTES", "30")))
    dry_run = os.environ.get("DRY_RUN") == "1"
    now = dt.datetime.now(dt.timezone.utc)

    stale = []
    for pr in paged(f"/repos/{repo}/pulls?state=open"):
        if pr["draft"]:
            continue
        sha = pr["head"]["sha"]
        runs = list(paged(f"/repos/{repo}/commits/{sha}/check-runs", key="check_runs"))
        statuses = request(f"/repos/{repo}/commits/{sha}/status")["statuses"]
        since = green_since(runs, statuses)
        if since is None or now - since <= limit:
            continue
        minutes = int((now - since).total_seconds() // 60)
        stale.append((pr, sha, minutes))

    lines = []
    for pr, sha, minutes in stale:
        number = pr["number"]
        lines.append(f"- #{number} {pr['title']}: green for {minutes} min (head {sha[:7]})")
        marker = MARKER.format(sha=sha)
        comments = paged(f"/repos/{repo}/issues/{number}/comments")
        if any(marker in c["body"] for c in comments):
            continue
        body = (
            f"{marker}\n"
            f"This PR has been green for {minutes} min (head {sha[:7]}) and is not merged. "
            "Address any open Codex comments, then merge it, or close it with a reason.\n\n"
            "_Posted by the Stale green PRs workflow (stale-prs.yml)._"
        )
        if dry_run:
            print(f"would comment on #{number}")
        else:
            request(f"/repos/{repo}/issues/{number}/comments", method="POST", body={"body": body})

    report = "\n".join(lines) if lines else "No open PR is green and unmerged for longer than the limit."
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(f"## Green PRs waiting to merge\n\n{report}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
