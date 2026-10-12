Arek's real recordings, replayed through the app's pure modules by the tests in
`src/test/kotlin/.../analysis/real` (the automated validation plan, workstream B). Each file is the
VBO he shared, gzip-compressed (`gzip -9 -n`); the tests read them through the app's session import,
which unwraps gzip itself.

| File | What | Rows |
|---|---|---|
| `silesia-ring-2026-09-27-racechrono.vbo.gz` | RaceChrono Pro 10.2.4, Silesia Ring, 27 Sep 2026: 23 min at 10 Hz with OBD, an out lap and seven timed laps. Unchanged. | 13,736 |
| `commute-2026-10-12-1036.vbo.gz` | BetterDriver 0.3.1 VBO export, 12 Oct 2026 10:36 UTC: a 38 min road drive at 25 Hz with phone video columns. | 54,797 |
| `commute-2026-10-12-0422.vbo.gz` | BetterDriver VBO export, 12 Oct 2026 04:22 UTC: the same road the other way, 23 min at 25 Hz. | 32,693 |
| `parked-2026-10-11-1427.vbo.gz` | BetterDriver VBO export, 11 Oct 2026 14:27 UTC: 6 min parked and crawling, with a 188 s gap in the fixes. | 4,734 |

The three BetterDriver files are road drives that start and end at private places, so before they
were added their positions were moved 10° west along their parallels: 600 arc minutes added to every
`long` value (VBO longitude is positive west), as text, so every other byte is as recorded. A turn
about the Earth's axis keeps every distance, heading, speed and curve, so timing and analysis are
unchanged. Silesia Ring is a public circuit and its file is unchanged.

Silesia Ring's timing lines are RaceChrono's own, from the file's `[laptiming]` section
(`RealCorpus.silesiaTrack`): a centre point and a second point along the track, the gate square to
them and as wide as they are apart, as FlappedEar Telemetry reads RaceChrono 10.2.4 gates; the car
drives from the second point towards the centre. Sector lines are the splits in number order. The
file holds no lap table of RaceChrono's. The road drives get a point-to-point track made from their
own fixes (`RealCorpus.roadTrack`).
