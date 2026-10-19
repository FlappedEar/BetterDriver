# analysis

Post-session analysis (Phase 3, FBBD-4). Pure Kotlin/JVM, a port of the analysis core of
[FlappedEar Telemetry](https://github.com/FlappedEar/Telemetry) (`packages/telemetry_core`,
Apache 2.0), which is itself a port of FlappedEar Overlays' C++. The port follows Telemetry line
by line so that both give the same results on the same data; file headers name the Dart file each
Kotlin file comes from.

## What is here

- **Recording model** (`data`): channels with strictly increasing times, interpolated reads that
  never bridge a gap (a gap is more than three median intervals), and the bucketed min/max
  sampling Telemetry analyses with.
- **Laps** (`laps`, FBBD-100): start/finish passes found from GPS (inner and outer corridor,
  must really cross the line, the crossing direction most passes take), laps between passes,
  and lap plausibility: GPS gaps, invalid fixes, and laps whose time, path length or average
  speed cannot be a lap of the circuit. Only eligible laps are ranked or used as references.
- **Track distance** (`progress`, FBBD-100): a ~2 m axis built from one lap, anchored at the
  start/finish crossing; heading and curvature along it; every lap projected onto it (a bounded,
  heading-checked search that refuses ambiguous fixes rather than guessing, so a hairpin's
  other leg or a parallel straight is never matched); the time delta between two laps by
  distance, 0 at the line and the lap-time difference at the finish.
- **Segments and the theoretical best** (`segments`, FBBD-101): corners and straights proposed
  from one lap's curvature (kinks under 0.35 rad stay in the straight; corners with no straight
  of 20 m between them form a chain, which can be split), boundaries marked uncertain near GPS
  gaps and short straights; a check that the lap's line agrees with the other laps; every lap's
  sector times on the shared axis (only fully covered sectors are timed); and the theoretical
  best, the fastest real time of each sector with the lap it came from.
- **Where time goes** (`segments`, FBBD-103): time-loss windows between two laps, one per segment
  (a straight right after a corner is marked as that corner's continuation), with the running
  delta at each end; every lap's losses against a reference lap, largest first; how repeatable
  each segment is (median and interquartile range, from three laps); and the theoretical best as
  the screens show it, with what the best lap leaves in each segment, largest first.
- **Session analysis** (`analyzeSession`): all of the above for one session. Segments are the
  track's lasting corner identity (`identity`, FBBD-172) placed on the fastest eligible lap's axis,
  or proposed on it when the track has none yet or the lap does not drive the identity's shape
  (a changed layout, or a track that became a circuit or a point-to-point course); those proposals
  become the identity's next revision. The driver's corner corrections (rename, move,
  split, merge, remove, retype, FBBD-179) are kept with the track in the app and reach analysis as
  that identity. Without segments (a lap that cannot be split, or a line far from the other laps)
  the axis and every lap's projection are still there for comparison.
- **Corner identity** (`identity`, FBBD-172, FBBD-215): corner and segment ids that stay the same
  across sessions and track revisions, and stay stable under float32 precision, noise, sample rate
  and missing samples.
- **Realistic optimal lap** (`optimal`, FBBD-178): the fastest chain of real laps whose segments
  join compatibly (speed, line, direction and longitudinal acceleration at each joint), named
  apart from the best actual lap and the raw theoretical best; also the clip plan for the
  optimal-lap video (FBBD-163).
- **Confidence** (`confidence`, FBBD-177): every pass, lap and conclusion graded High, Medium or
  Low with its reasons; Low gives no advice, Medium gives advice without numbers.
- **Corners and driving** (`corners`, `driving`, FBBD-175): braking point, minimum and exit speed,
  throttle pickup, each pass's traces and video window, driving states (braking, coasting,
  cornering, accelerating, full throttle) and coasting per corner.
- **Focus and coaching history** (`focus`, `coaching`, FBBD-20, FBBD-180): one primary focus and
  up to two secondary for the next session, and what came of each focus in later sessions.
- **Progression** (`progression`, FBBD-181): laps and corners per session, track day or month at a
  track, with trends.
- **Maps and runs** (`map`, `runs`): lap map, G-G diagram and acceleration runs.
- **Recorded sessions** (`recorded`, FBBD-102): a recorder session as analysis input, with the
  track's start/finish line as the start gate.
- **Lap comparison** (`compare`, FBBD-102): two laps by distance on the session's axis, the time
  delta and both speed traces, for the charts.
- **Session validation report** (`quality`, FBBD-183): the P0 pass criteria (FBBD-8, -12/61, -21,
  -27/49, -43, -46, -72) checked against what a session recorded: GNSS rate, drops, gaps,
  reconnects and parked noise; laps and the predicted lap time at the line; recorder drops and
  restarts; video offset, drift and coverage; coach cue delay and margin; Android thermal shutdown, only if one happened. Each
  value is PASS, FAIL or NOT_MEASURED (with why), and says whether it was read straight from the
  data (MEASURED) or worked out by a stated heuristic (DERIVED). The app stores it as
  `quality.json` in the session's folder and puts it in the debug-log zip.

## Tests

`src/test/resources/parity` holds Telemetry's synthetic VBO corpus and its parity references,
generated by Overlays' C++. Each test reads the files the way Telemetry does (`VboFixture`, values
stored as float32 like Telemetry) and requires the same results to 1e-9:

- `ProgressParityTest` (`progress_reference.json`): laps, axis, features, projections and deltas.
- `SegmentsParityTest` (`segments_reference.json`): every lap's proposals, coverage gaps and
  proposals on the reference lap's axis, and option and gap variants.
- `TheoreticalBestParityTest` (`theoretical_best_reference.json`): sector times, theoretical best,
  its published summary with consistency, and time-loss rankings and windows for each file's
  segments, shifted segments and every other segment, plus hand-made cases.
