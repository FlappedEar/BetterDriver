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
FBBD issue.

Screen consistency (FBBD-394): Android is the reference for each screen's structure. An ordered list
of tabs, sections or actions is marked in the code of both platforms with a comment
`// parity-list:<name>` on the line above its declaration (an enum, a listOf, a TabView). The list is
the first string literal of each line at the block's first level, in order. Each list has a row in
the "Screens" table of docs/parity.md with the Android order; every marker of it must give that
order. While the row's iOS cell has a gap mark and a FBBD issue, an iOS screen may lack the marker.

Run from the repository root: python3 tools/parity/check.py [--keys | --lists]
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


LIST_MARK = re.compile(r"^\s*//\s*parity-list:([a-z0-9-]+)\s*$")
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def block_items(lines, start):
    """
    The first string literal of each first-level line of the block that opens at or after [start].
    An `if` or `else` block inside it is see-through, so an item shown only on one device counts.
    """
    stack, opened, items = [], False, []
    for line in lines[start:]:
        code = re.sub(r"//.*$", "", line)
        stripped = code.strip()
        level = sum(1 for see_through in stack if not see_through)
        if opened and level == 1 and stripped and not stripped.startswith(("*", "/*", "}")):
            m = LITERAL.search(code)
            if m:
                items.append(m.group(1))
        conditional = re.match(r"(?:\}\s*)?(?:if\b|else\b|#if\b)", stripped) is not None
        for ch in LITERAL.sub('""', code):
            if ch in "{([":
                stack.append(conditional and ch == "{" and opened)
                opened = True
            elif ch in "})]" and stack:
                stack.pop()
        if opened and not stack:
            return items
    return items


def code_lists():
    """{(platform, name): [(path:line, items)]} of every parity-list marker."""
    out = {}
    sources = [("Android", p) for p in android_sources()]
    sources += [("iOS", p) for p in files("ios/BetterDriver/**/*.swift") + ios_kotlin_sources()]
    for platform, path in sources:
        lines = path.read_text().splitlines()
        for i, line in enumerate(lines):
            m = LIST_MARK.match(line)
            if m:
                where = f"{path.relative_to(ROOT)}:{i + 1}"
                out.setdefault((platform, m.group(1)), []).append((where, block_items(lines, i + 1)))
    return out


def screen_rows():
    """Rows of the Screens table: (line, screen, name, order, ios, jira)."""
    rows, inside = [], False
    for n, line in enumerate(DOC.read_text().splitlines(), 1):
        if line.startswith("## "):
            inside = line.strip() == "## Screens"
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not inside or len(cells) != 5 or not line.startswith("|") or cells[0] == "Screen" or set(cells[0]) <= {"-"}:
            continue
        screen, name, order, ios, jira = cells
        rows.append((n, screen, name.strip("`").removeprefix("list:"), [o.strip() for o in order.split(",")], ios, jira))
    return rows


def check_lists(errors):
    found = code_lists()
    named = set()
    for n, screen, name, order, ios, jira in screen_rows():
        named.add(name)
        if not ios.startswith(KNOWN_MARKS):
            errors.append(f"docs/parity.md:{n}: {screen}: iOS status must start with one of {' '.join(KNOWN_MARKS)}")
        ios_gap = ios.startswith(GAP_MARKS)
        if ios_gap and not re.search(r"FBBD-\d+", jira):
            errors.append(f"docs/parity.md:{n}: {screen}: iOS differs, so the row needs a FBBD issue")
        if ("Android", name) not in found:
            errors.append(f"docs/parity.md:{n}: {screen}: no `// parity-list:{name}` in the Android code")
        if ("iOS", name) not in found and ios.startswith(("✅", "🔵")):
            errors.append(f"docs/parity.md:{n}: {screen}: no `// parity-list:{name}` in the iOS code")
        # A gap only excuses an iOS screen not marked yet; every marked list is in the Android order.
        for platform in ("Android", "iOS"):
            for where, items in found.get((platform, name), []):
                if items != order:
                    errors.append(f"{where}: {screen} on {platform} is {', '.join(items) or 'empty'}; "
                                  f"Android order (docs/parity.md:{n}) is {', '.join(order)}")
    for platform, name in sorted(found):
        if name not in named:
            where = found[(platform, name)][0][0]
            errors.append(f"{where}: parity-list:{name} is in no row of the Screens table in docs/parity.md")
    return found


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
    if "--lists" in sys.argv:
        for (platform, name), marks in sorted(code_lists().items()):
            for where, items in marks:
                print(f"{name} {platform} {where}: {', '.join(items)}")
        return 0
    errors = []
    lists = check_lists(errors)
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
        print(f"Parity: {len(found)} platform keys, all in docs/parity.md; "
              f"{sum(len(m) for m in lists.values())} screen lists in the Android order.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
