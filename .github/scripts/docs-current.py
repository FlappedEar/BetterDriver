#!/usr/bin/env python3
"""Checks that the docs describe the release (docs/RELEASE_CHECKLIST.md, section 3).

Usage: docs-current.py <version> [<previous release version>]

Fails when:
- README.md or the user guide's home page does not name every platform the repository builds
  (Android always; iPhone and iPad when ios/ holds the iOS app), or still says "Android only";
- the guide's home page does not say it describes this version, or "What's new" has no section
  for it;
- a Confluence page in docs/confluence-pages.md is checked before the previous release.

Prints the pages checked before this version: they are updated before the release counts as done.
"""
import os
import re
import sys


def version_key(v):
    return tuple(int(p) for p in v.split("."))


def main():
    version = sys.argv[1]
    previous = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else None
    errors = []

    platforms = ["Android"]
    if os.path.exists("ios/project.yml"):
        platforms += ["iPhone", "iPad"]
    for path in ("README.md", "guide/docs/index.md"):
        text = open(path, encoding="utf-8").read()
        intro = text[:2000]
        for name in platforms:
            if name not in intro:
                errors.append(f"{path} does not name {name} near the top; it must cover every platform.")
        if re.search(r"android only", text, re.IGNORECASE):
            errors.append(f'{path} still says "Android only".')

    index = open("guide/docs/index.md", encoding="utf-8").read()
    if f"**BetterDriver {version}**" not in index:
        errors.append(f"guide/docs/index.md does not say it describes **BetterDriver {version}**.")
    news = open("guide/docs/whats-new.md", encoding="utf-8").read()
    if not re.search(rf"^## {re.escape(version)}\s*$", news, re.MULTILINE):
        errors.append(f"guide/docs/whats-new.md has no '## {version}' section.")

    stale = []
    rows = re.findall(r"^\| (\d+) \| (.+?) \| (.+?) \|$", open("docs/confluence-pages.md", encoding="utf-8").read(), re.MULTILINE)
    if not rows:
        errors.append("docs/confluence-pages.md lists no pages.")
    for page, title, checked in rows:
        if checked == "won't change":
            continue
        if not re.fullmatch(r"\d+\.\d+\.\d+", checked):
            errors.append(f"docs/confluence-pages.md: '{title}' has '{checked}', not a version.")
            continue
        if version_key(checked) < version_key(version):
            stale.append((page, title, checked))
        if previous and version_key(checked) < version_key(previous):
            errors.append(f"Confluence '{title}' ({page}) is checked at {checked}, before the previous release {previous}: update it first.")

    for page, title, checked in stale:
        print(f"TO UPDATE after {version}: {title} (page {page}, checked at {checked})")
    for e in errors:
        print(f"::error::{e}")
    if not errors:
        print(f"Docs name {', '.join(platforms)} and describe {version}; {len(stale)} Confluence pages to update after it.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
