# Confluence pages and the release they were last checked at

Every page in Confluence space B, with the release its "checked" line names. The release workflow
reads this table (`.github/scripts/docs-current.py`):

- it lists in the run summary every page checked before the version being released: those pages
  are updated (summary re-checked, "checked" line bumped) before the release counts as done;
- it refuses to publish while any page is still checked before the previous release, so a release
  whose pages were not caught up blocks the next one.

After updating a page, set its row here to the version it was checked at (in the release's
post-release PR, or with the next PR). A new page gets a row; a deleted page loses its row.
"won't change" marks a page kept only as a record (it is not checked at each release).

| Page id | Title | Checked at |
|---|---|---|
| 12648561 | BetterDriver Home | 1.26.0 |
| 12976129 | BetterDriver status | 1.26.0 |
| 16482306 | What blocks 1.0 | 1.26.0 |
| 14483457 | BetterDriver plan of record: closed-loop coach | 1.26.0 |
| 32505866 | Getting faster loop (line, coach, 3D, proof) | 1.26.0 |
| 15007745 | BetterDriver validation without test drives | 1.26.0 |
| 36372481 | iOS / iPadOS app plan | 1.26.0 |
| 39682049 | Android and iOS feature parity | 1.26.0 |
| 12779559 | Architecture | 1.26.0 |
| 12615683 | Recorder: crash-safe session format and VBO export | 1.26.0 |
| 12910622 | RaceBox Micro BLE protocol | 1.26.0 |
| 14417921 | VBO video format (Circuit Tools) | 1.26.0 |
| 25919489 | Steering angle: CAN listen beside OBD-II polling | 1.26.0 |
| 12648641 | BetterDriver Build Plan | 1.26.0 |
| 12877846 | Roadmap | 1.26.0 |
| 12746768 | BetterDriver Development Workflow | 1.26.0 |
| 12976197 | Design and UI | 1.26.0 |
| 12812319 | BetterDriver UI decisions | 1.26.0 |
| 40665106 | Working rules | 1.26.0 |
| 12648738 | Racelogic VBOX track maps evaluation | won't change |
