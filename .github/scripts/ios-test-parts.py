"""Which iOS test jobs a run has, and which tests each runs (FBBD-457).

Usage:
  ios-test-parts.py matrix pr|main-rest|full   the test jobs, as the JSON of a job matrix
  ios-test-parts.py args <device> <part>       xcodebuild's test selection for one job, one per line

Scopes:
- pr: a pull request runs everything on the iPhone, in three parts of about equal time, and on the
  iPad only what checks the iPad itself (its layout, the review screens and their screenshots, the
  iPad's first start). The two Mac runners take two jobs at a time, so every test that runs twice
  doubles the wait.
- main-rest: a push to main whose tree a pull request run already passed (its "iOS passed (tree
  ...)" job) runs the rest of the iPad's UI tests, so every test still runs on both devices
  before a release.
- full: everything on both devices (a merge no pull request run tested, a run from the Actions tab).

Each part but the last names its classes; the last one runs everything else, so a new test class
always runs. The unit tests (BetterDriverTests) check no layout: they run on the iPhone only.
"""
import json
import sys

UI = "BetterDriverUITests"
UNIT = "BetterDriverTests"

# Measured on the Mac minis (PR #544 run, 2026-11-04): about 12-14 min a part on the iPhone.
PARTS = [
    ["ReviewScreensTests", "ScreenLayoutTests", "MapLapsTests", "LapPickerTests", "MakeTrackTests",
     "ProgressionTests", "TrackCornersTests"],
    ["DataVideoTests", "ShareEntryPointsTests", "SatelliteMapTests", "BackupUiTests", "EveryPassTests"],
]
LAST = len(PARTS) + 1

# What only the iPad shows: run on the iPad in every pull request.
IPAD_OWN = ["IPadLayoutTests", "ScreenLayoutTests", "ReviewScreensTests", "FirstRunTests"]


def matrix(scope):
    phone = [{"device": "iPhone", "part": str(p)} for p in range(1, LAST + 1)]
    pad = [{"device": "iPad", "part": str(p)} for p in range(1, LAST + 1)]
    jobs = {
        "pr": phone + [{"device": "iPad", "part": "own"}],
        "main-rest": [{"device": "iPad", "part": "rest"}],
        "full": phone + pad,
    }[scope]
    return {"include": jobs}


def args(device, part):
    out = []
    if part == "own":
        out = [f"-only-testing:{UI}/{c}" for c in IPAD_OWN]
    elif part == "rest":
        out = [f"-skip-testing:{UI}/{c}" for c in IPAD_OWN]
    elif int(part) < LAST:
        out = [f"-only-testing:{UI}/{c}" for c in PARTS[int(part) - 1]]
    else:
        out = [f"-skip-testing:{UI}/{c}" for group in PARTS for c in group]
    # -only-testing already leaves the unit tests out.
    if device == "iPad" and not any(a.startswith("-only-testing") for a in out):
        out.append(f"-skip-testing:{UNIT}")
    return out


if __name__ == "__main__":
    if sys.argv[1] == "matrix":
        print(json.dumps(matrix(sys.argv[2])))
    else:
        print("\n".join(args(sys.argv[2], sys.argv[3])))
