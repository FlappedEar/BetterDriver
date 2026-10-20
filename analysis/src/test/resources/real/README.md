Arek's real recordings, replayed through the app's pure modules by the tests in
`src/test/kotlin/.../analysis/real` (the automated validation plan, workstream B). Each file is the
VBO he shared, gzip-compressed (`gzip -9 -n`); the tests read them through the app's session import,
which unwraps gzip itself.

| File | What | Rows |
|---|---|---|
| `silesia-ring-2026-09-27-racechrono.vbo.gz` | RaceChrono Pro 10.2.4, Silesia Ring, 27 Sep 2026: 23 min at 10 Hz with OBD, an out lap and seven timed laps. Unchanged. | 13,736 |
| `silesia-ring-2026-09-27-1353-racechrono.vbo.gz` | The same day's second session, from 13:53 local: 29 min at 10 Hz with OBD, the same lines. Unchanged. | 17,517 |
| `silesia-ring-2026-09-27-1454-racechrono.vbo.gz` | The same day's third session, from 14:54 local: 29 min at 10 Hz with OBD, the same lines. Unchanged. | 17,322 |
| `jastrzab-2026-08-29-1000-racechrono.vbo.gz` | RaceChrono Pro 10.2.4, Autodrom Jastrząb, 29 Aug 2026 from 10:00 local: 10 Hz with OBD, RaceChrono's start line, no splits. Unchanged. | 15,578 |
| `jastrzab-2026-08-29-1129-racechrono.vbo.gz` | RaceChrono Pro 10.2.4, Autodrom Jastrząb, 29 Aug 2026 from 11:29 local: 10 Hz with OBD, RaceChrono's start line, no splits. Unchanged. | 15,729 |
| `jastrzab-2026-08-29-1303-racechrono.vbo.gz` | RaceChrono Pro 10.2.4, Autodrom Jastrząb, 29 Aug 2026 from 13:03 local: 10 Hz with OBD, RaceChrono's start line, no splits. Unchanged. | 12,932 |
| `jastrzab-2026-08-29-1437-racechrono.vbo.gz` | RaceChrono Pro 10.2.4, Autodrom Jastrząb, 29 Aug 2026 from 14:37 local: 10 Hz with OBD, RaceChrono's start line, no splits. Unchanged. | 12,019 |
| `jastrzab-2026-08-29-1606-racechrono.vbo.gz` | RaceChrono Pro 10.2.4, Autodrom Jastrząb, 29 Aug 2026 from 16:06 local: 10 Hz with OBD, RaceChrono's start line, no splits. Unchanged. | 13,819 |
| `jastrzab-2026-08-29-1720-racechrono.vbo.gz` | RaceChrono Pro 10.2.4, Autodrom Jastrząb, 29 Aug 2026 from 17:20 local: 10 Hz with OBD, RaceChrono's start line, no splits. Unchanged. | 9,093 |
| `commute-2026-10-12-1036.vbo.gz` | BetterDriver 0.3.1 VBO export, 12 Oct 2026 10:36 UTC: a road drive at 25 Hz with phone video columns, 27 min of it after trimming. | 39,030 |
| `commute-2026-10-12-0422.vbo.gz` | BetterDriver VBO export, 12 Oct 2026 04:22 UTC: the same road the other way at 25 Hz, 12 min of it after trimming. | 17,026 |

The two road drives start and end at private places. Every fix within 1.5 km of a drive's first or
last fix was removed, and the rest were moved by an undisclosed offset. Silesia Ring and Autodrom
Jastrząb are public circuits and their files are unchanged; every Jastrząb fix is within 600 m of the
circuit.

Silesia Ring's timing lines are RaceChrono's own, from the file's `[laptiming]` section
(`RealCorpus.silesiaTrack`): a centre point and a second point along the track, the gate square to
them and as wide as they are apart, as FlappedEar Telemetry reads RaceChrono 10.2.4 gates; the car
drives from the second point towards the centre. Sector lines are the splits in number order. The
file holds no lap table of RaceChrono's. The road drives get a point-to-point track made from their
own fixes (`RealCorpus.roadTrack`). Jastrząb's line is RaceChrono's start trap read the same way
(`RealCorpus.jastrzabTrack`); its files have no splits.
