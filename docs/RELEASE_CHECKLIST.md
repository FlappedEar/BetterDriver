# Release acceptance criteria

A release is not ready because the JVM tests are green, and a feature is not
done because its code exists. Every release passes two gates.

## 1. Automatic gate (CI)

`release.yml` signs and publishes only a commit whose whole CI run succeeded
(`ci-green` job): main's run of that commit, or the pull request run that
checked the very same source tree (its `CI passed (tree …)` job, when the pull
request was squash-merged while up to date with main). Each main commit keeps
its own CI run, so a later merge no longer cancels the run a release waits for.
That run includes:

- unit tests (with the analysis parity tests against FlappedEar Telemetry and
  the screenshot tests), Android lint, detekt and the APK build;
- the **emulator smoke tests** (FBBD-161): the debug app on an Android 14
  emulator with seeded sessions and tracks, opening every major screen and
  flow. The job fails when a test fails, when a screen crashes, when
  navigation breaks, when seeded data cannot be opened, when data stored by
  an older build (track file version 1, 0.3.1 session metadata) cannot be
  opened, when a Demo GNSS recording does not bring up and keep the
  `:recorder`, `:camera` and `:coach` processes or is not saved with its laps,
  or when any app process crashes or stops responding (ANR), as logcat and
  `ActivityManager` report it.

## 2. Acceptance record (every release)

Each release has `docs/release-notes/v<version>.md`, merged with the version
bump. The release workflow refuses to publish when the file is missing, when
one of the areas below is missing, or when any area says `FAIL`.

Each area is one line, `- <Area>: <PASS | NOT RUN | N/A> - <evidence>`, where
the evidence names the test, issue or log that shows it. `NOT RUN` is allowed
but must be honest: it says which real-device check has not been done yet,
and the Jira issue that carries it (usually in Pending Arek with numeric pass
criteria).

| Area | Questions |
| --- | --- |
| Recording | Can a full session be recorded without corruption? Can the UI fail without destroying the recording? Does GNSS reconnect? Can sensors disappear independently? |
| Timing | Is timing validated against RaceChrono or a known reference? Are circuit and point-to-point both covered? |
| Video | Is sync acceptable for a full session (start error, drift at 10/20/30 min)? Does video survive realistic phone conditions? |
| Analysis | Are the major analysis flows deterministic and covered by tests? Do the Telemetry parity tests still hold where intended? |
| Coach | Is cue timing safe (effective delay at the helmet)? Is audio routed correctly? Does advice come from valid reference data, and is it suppressed when confidence is weak? |
| Storage | Are stored-data migrations safe? Do backup and restore work? Can sessions be transferred? |
| Hardware | RaceBox, the intended GoPro path, the phone, tablet review, and OBD once it exists. |

The numeric pass criteria for the real-device checks live in their Jira issues
(FBBD-8, 12, 21, 22, 24, 27, 34, 43, 46, 49, 61, 68, 72, 82, 93 and others
labelled P0). The plan of record is the Confluence page "BetterDriver plan of
record: closed-loop coach".

## 3. After publishing (every release)

A release is reported done only after these, in the same session:

1. Confluence "BetterDriver Home" (page 12648561): the banner's green badge
   says "Alpha X.Y.Z released" and its "Latest:" line names the version, the
   date and what changed.
2. Confluence "BetterDriver status" (page 12976129): a Now entry and a new
   row at the top of the Releases table.
3. Confluence "What blocks 1.0" (16482306), the plan of record (14483457) and
   "BetterDriver validation without test drives" (15007745): on every
   release, re-check each page's summary and bump its "checked" date and
   release, even when the release does not change them.
4. Every other Confluence page whose subject the release touches
   (Architecture, Recorder, RaceBox Micro BLE protocol, Steering angle, VBO
   video format, UI decisions, Roadmap, the Build Plan's history panel):
   update its summary and its "checked" date and release.
5. Jira: the issues the release ships moved to their real state.
6. Every Confluence page in `docs/confluence-pages.md` checked before this
   release: summary re-checked, "checked" line bumped, and its row set to this
   version in a PR. The release run lists these pages under "Docs for X.Y.Z".

The release workflow's "Docs are current" step (`.github/scripts/docs-current.py`)
refuses to publish when README.md or the user guide's home page does not name
every platform (Android, iPhone and iPad), when the guide does not say it
describes this version or "What's new" has no section for it, or when any
Confluence page is still checked before the previous release. So a release
whose pages were not caught up blocks the next one.

The release run's summary repeats this list. From 0.3.25 to 0.4.0 the Home
banner was not part of the steps and stayed at 0.3.24 for 20 releases. Until
1.7.1 step 3 said "where the release changes them", so those pages were
skipped and eleven pages stayed at 1.0.2 (found 2026-10-22). A page whose
"checked" line names an older release than the latest one is stale.

## Template

```markdown
Short summary of what changed for the driver.

## Acceptance
- Recording: PASS - ...
- Timing: NOT RUN - FBBD-61 (lap times vs RaceChrono) pending a track day
- Video: ...
- Analysis: ...
- Coach: ...
- Storage: ...
- Hardware: ...
```
