# timing

Tracks and live timing. Pure Kotlin/JVM, tested on the JVM with simulated 25 Hz
laps and the recorder's fake GNSS (FBBD-54, 55, 57 to 60, 79). It reads the
recorder's live output through a `RecorderListener`; the recorder never depends
on it, so a timing failure cannot affect a recording (FBBD-69).

## Tracks (FBBD-54)

A `Track` has a name, an optional layout, a centre and radius for detection,
a start/finish line, sector lines in lap order (n lines make n + 1 sectors), and
optional pit entry and pit exit lines. Each line has a direction: a car crosses
it forwards with `left` on its left. `TrackLine.across` makes a line square to a
heading, which is how a line is taken from a recorded lap.

`TrackStore` keeps one JSON file per track (`<id>.track.json`, format
`betterdriver-track`, version 1). Writes are synced and renamed into place, so a
crash leaves the old or the new track, never half of one. Files that cannot be
read are renamed aside, never overwritten. The same file is what moves between
phone and tablet (FBBD-80).

## Circuit directory (FBBD-79)

`circuits/circuits.json` on the classpath is the Wikidata list (CC0 1.0) from
FlappedEar Telemetry: 999 circuits, a name and one position each, no lines.
`tools/circuits/update_circuits.py` rebuilds it (a port of Telemetry's Dart
tool). `CircuitDirectory.refresh` fetches the same file from `main` on GitHub and
keeps it when its revision is newer; without a connection the bundled copy is
used. Only 8 Polish circuits are on the list as of revision 2026100610; tracks it
lacks are made in the editor from the car's position.

## Detection (FBBD-55)

`detectTrack` runs once, at session start: the nearest saved track whose radius
(2 km by default) reaches the car, else the nearest directory circuit, else
unknown. It never prompts; offering to create a track belongs after the outing.

## Timing (FBBD-57 to FBBD-60)

- **Crossings.** A crossing between two fixes is interpolated linearly in time;
  crossings the wrong way are ignored. On simulated 25 Hz data, with the car always
  accelerating or braking, every crossing is within 5 ms of the true time.
- **Laps.** Laps run start/finish to start/finish; the first is an out lap. A
  lap that crosses pit entry is an in lap; one that crosses pit exit is an out
  lap. Without a pit exit line, the lap after an in lap is an out lap. Passes
  under 10 s apart are ignored. Missed sector lines leave only their sectors
  untimed. Bests come from timed laps only.
- **Predictive delta.** The reference is the session's best lap. Each position is
  matched to the nearest point of the reference path, searching only near the
  last match so a hairpin's other leg is never picked; the delta is the time
  taken to get there minus the reference's. At the line it equals the true lap
  time difference. Predicted lap = best + delta.
- **Track screen.** `LiveTiming` gives the delta, predicted, last and best lap,
  and per-sector gains against the best lap and against the best sectors.
  `feature-track`'s `withTiming` fills the approved track screen from it.
  `timeSession` re-times a recorded session, for example after editing lines.

## In the app

`SessionTiming` is the recorder listener the recorder process adds to every
recording. At the first real position it reads the saved tracks, detects the
track, and from then on times every fix against it if it has a start/finish
line. The detection is written into the session as a `track` event; the track's
name, lap, delta and sectors go to the track screen with the recorder's live
state.

## Video overlay (FBBD-152)

`timing.overlay` is the data behind the app's "Video with data" export, pure so
it is tested here; the app draws it into the video with Media3 Transformer.

- **`OverlayData`** looks up speed, position, G and the lap at any session time,
  interpolating between samples (nothing across a gap of over 2 s). The delta is
  to the session's best lap, matched by position as on the track screen; out and
  in laps have none. G is the accelerometers' as the analysis corrects them
  (`measuredG`: mount fitted to GPS, a RaceBox's and the phone's averaged,
  FBBD-226), else from GNSS: change of speed, and speed times the path's turn rate.
- **`videoCuts`** turns a time range (a lap, or everything) into pieces of the
  camera's files, back to back; a lap across two files is two cuts. Each cut maps
  a frame's time in the export back to session time.
- **`OverlayLayout`**, **`MapFit`** and **`gDot`** place the band along the
  bottom of the picture, scaled from one unit so every resolution looks the same.
