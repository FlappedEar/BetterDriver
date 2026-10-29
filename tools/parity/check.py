#!/usr/bin/env python3
"""Android and iOS feature parity check (FBBD-361).

Every place where one platform has code of its own must be named in a row of docs/parity.md, so a
feature or a log line added to one app shows up as a row with the other app's status and, while the
other app lacks it, a Jira issue. Logic in the shared Kotlin core (commonMain) needs no row: both
apps run it.

The keys, read from the code:
  route:<Name>        an Android screen (a destination in app Routes.kt)
  ios-screen:<Name>   an iOS screen (a top-level SwiftUI struct named ...Page/View/Tab/Sheet/Screen)
  log-android:<Tag>   a log tag only Android code writes (src/main, jvmMain, androidMain)
  log-ios:<Tag>       a log tag only iOS code writes (Swift, iosMain)

The check fails when a key in the code is in no row, when a row names a key the code no longer has,
or when a row says one platform lacks the feature (missing, partial, to verify, planned) without a
FBBD issue. Run from the repository root: python3 tools/parity/check.py [--keys]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOC = ROOT / "docs" / "parity.md"

# Status marks of the Android and iOS columns; these say the platform lacks the feature.
GAP_MARKS = ("❌", "🟡", "🟣", "⚪")
KNOWN_MARKS = ("✅", "🔵", "➖") + GAP_MARKS

ANDROID_DIRS = ("src/main", "src/jvmMain", "src/androidMain")
WORD = r"[A-Z][A-Za-z0-9]*"


def files(pattern):
    return [p for p in ROOT.glob(pattern) if "/build/" not in p.as_posix()]


def android_sources():
    out = []
    for module in sorted(p for p in ROOT.iterdir() if p.is_dir() and (p / "build.gradle.kts").exists()):
        if module.name == "tools":
            continue
        for d in ANDROID_DIRS:
            out += [p for p in (module / d).rglob("*.kt") if "/build/" not in p.as_posix()]
    return out


def ios_kotlin_sources():
    return [p for p in ROOT.glob("*/src/iosMain/**/*.kt") if "/build/" not in p.as_posix()]


def code_keys():
    keys = set()
    routes = (ROOT / "app/src/main/kotlin/com/flappedear/betterdriver/Routes.kt").read_text()
    for name in re.findall(r'const val (\w+) = "', routes):
        if not name.endswith("Arg") and not name.startswith("New"):
            keys.add(f"route:{name}")
    for swift in files("ios/BetterDriver/**/*.swift"):
        for name in re.findall(r"^(?:private |fileprivate )?struct (\w+(?:Page|View|Tab|Sheet|Screen))\s*:\s*View",
                               swift.read_text(), re.M):
            keys.add(f"ios-screen:{name}")
    tag_const = re.compile(rf'const val (?:Tag|TAG|\w+Tag) = "({WORD})"')
    android_call = re.compile(rf'DebugLog\.(?:[diwe]|forSession|log)\(\s*(?:[^,()]+,\s*)?"({WORD})"')
    for kt in android_sources():
        text = kt.read_text()
        if "DebugLog" not in text and "logInfo" not in text and "logLine" not in text:
            continue
        keys |= {f"log-android:{t}" for t in tag_const.findall(text) + android_call.findall(text)}
    ios_call = re.compile(rf'(?:AppLog\.[diwe]\(\s*|\.write(?:Now)?\(\s*"[DIWE]",\s*)"({WORD})"')
    ios_const = re.compile(rf'static let tag = "({WORD})"')
    for swift in files("ios/BetterDriver/**/*.swift"):
        text = swift.read_text()
        keys |= {f"log-ios:{t}" for t in ios_call.findall(text) + ios_const.findall(text)}
    for kt in ios_kotlin_sources():
        text = kt.read_text()
        if "logInfo" not in text and "logLine" not in text:
            continue
        keys |= {f"log-ios:{t}" for t in tag_const.findall(text) + re.findall(rf'logInfo\(\s*"({WORD})"', text)}
    return keys


def doc_rows():
    rows = []
    for n, line in enumerate(DOC.read_text().splitlines(), 1):
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 7 or not line.startswith("|") or cells[0] in ("Area", "---") or set(cells[0]) <= {"-"}:
            continue
        area, feature, android, ios, code, jira, keys = cells
        rows.append((n, feature, android, ios, jira, re.findall(r"`([a-z-]+:[A-Za-z0-9]+)`", keys)))
    return rows


def main():
    found = code_keys()
    if "--keys" in sys.argv:
        print("\n".join(sorted(found)))
        return 0
    errors = []
    listed = {}
    for n, feature, android, ios, jira, keys in doc_rows():
        for platform, status in (("Android", android), ("iOS", ios)):
            if not status.startswith(KNOWN_MARKS):
                errors.append(f"docs/parity.md:{n}: {feature}: {platform} status must start with one of {' '.join(KNOWN_MARKS)}")
            elif status.startswith(GAP_MARKS) and not re.search(r"FBBD-\d+", jira):
                errors.append(f"docs/parity.md:{n}: {feature}: {platform} lacks it, so the row needs a FBBD issue")
        for k in keys:
            if k in listed:
                errors.append(f"docs/parity.md:{n}: {k} is also in line {listed[k]}")
            listed[k] = n
    for k in sorted(found - listed.keys()):
        errors.append(f"{k} is in the code but in no row of docs/parity.md: add it to the feature's row "
                      "(or a new row with the other platform's status and a Jira issue)")
    for k in sorted(listed.keys() - found):
        errors.append(f"docs/parity.md:{listed[k]}: {k} is no longer in the code: remove it from the row")
    for e in errors:
        print(f"::error::{e}" if "GITHUB_ACTIONS" in __import__("os").environ else e)
    if not errors:
        print(f"Parity: {len(found)} platform keys, all in docs/parity.md.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
