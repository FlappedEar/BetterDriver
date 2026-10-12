# coach

The live coach (Phase 4, epic FBBD-6), Catalyst style, on GNSS data only. Pure
Kotlin/JVM, tested on the JVM with simulated laps on a made-up circuit. It reads
the recorder's samples and the timing module's laps; nothing depends on it, and
the app runs it in its own process, so a coach failure never reaches a recording
(FBBD-69).

## Corners (FBBD-96)

`findCorners` takes one lap's path, resampled every 2 m, and lists its braking
corners: every speed minimum that is the lowest speed within 40 m either side and
comes after a drop of at least 8 km/h from the fastest point since the previous
corner. That fastest point is the braking point. Flat-out bends are not listed,
so the numbers ("Turn 3") are the coach's own, not the circuit's.

A `CornerMap` is the reference lap's path with its corners. Positions of any lap
are placed on it by distance along the path (the predictive delta's matching,
FBBD-60), so laps on different lines compare. Each corner has a measuring window
from 120 m before its braking point to 60 m after its apex; a `CornerPass` is one
drive through it: braking point, apex, minimum speed and time across the window.

## Reference from earlier sessions (FBBD-26, FBBD-96)

`CoachLibrary` keeps one `<track id>.coach.json` per saved track. The fastest
timed lap over all sessions at that track gives the path and the corners; every
timed lap's passes are measured against it and the fastest pass of each corner is
that corner's target, so a cue only asks for what the driver has already done
there. Which track each session was at is read once from its `track` event and
remembered, so only new sessions are opened. A reference is rebuilt when a session
at the track appears or disappears, or when the track's lines change (revision).

## Cue engine (FBBD-27, FBBD-97)

`CueEngine` is fed every fix of a recording at a saved track with a start/finish
line and returns what to say and when.

- **Learning.** No corner cue until 3 timed laps of the session are done (setting).
  Without an earlier reference, the session's first timed lap gives the corners.
- **Targets** start from the reference and take any faster pass of this session.
- **Advice** compares the last lap's pass with the target, when it lost at least
  50 ms: braked 8 m or more earlier, "brake N metres later" (multiples of 5, at
  most 20); slower by 3 km/h or more at the apex after braking 8 m or more later,
  "brake earlier"; otherwise slower at the apex, "carry N more" (at most 10).
- **One cue per straight.** The cue for the next corner is spoken once the car is
  30 m past the previous apex, not braking, and only if it will be heard in full,
  after the audio delay setting (0 to 2 s) and 0.3 s of latency, at least 0.3 s
  before the braking zone. It starts at most 1.5 s before it must. If the straight
  is too short it is skipped. The time to the braking zone is the shorter of the
  time at the current speed and the reference lap's time, since the car is still
  accelerating out of the corner. A corner cued on one lap gets the next lap to
  try it before it is cued again.
- **Verbosity.** Off; lap times; focus (the 3 corners losing the most over their
  last 3 passes, plus lap times); full. Lap times are spoken on the straight after
  the line only when that straight has no corner cue.
- **Results.** The pass right after a cue says whether the driver moved at least
  half of what was asked, and the time gained across the corner (FBBD-28).

## Picked corners (FBBD-148)

In a session's lap analysis (Summary tab) the driver can pick corners for the coach: "Coach this next
time" on a next-session focus area at a corner, or the flag on a corner in "Where time goes". Picks are
kept per track in `filesDir/coach/picks/<track id>.json` (`CoachPicksStore`, written to a temporary file,
synced and renamed into place) and read by the coach at each session start. The coach settings page lists
each track's picks with a Clear.

- **Matching.** The analysis and the coach number corners differently: the analysis splits its fastest
  lap by curvature ("Corner 3", flat-out bends too, and a quick left-right may be one "Corners 3–4"),
  while the coach lists only the corners its reference lap brakes for, in lap order. Distances along the
  lap do not line up either, since each measures along its own reference lap from the line. So a pick is
  stored as where the corner is: the analysis lap's line through it, a position every 5 m from its start
  to its end (`segmentLine` in `analysis`). A coach corner is picked when its apex is within 25 m of a
  picked line and the reference lap runs the same way there (`pickedCorners`). A pick covering two
  braking corners picks both; a pick of a bend taken flat out matches nothing. The same corner picked
  again from another session (the middles of both lines within 15 m of the other line) toggles it off.
- **Focus.** The picked corners take the 3 focus places first (those losing most, when more are
  picked); the corners losing most fill the rest.
- **Competing cues.** Cues for consecutive corners never overlap: each is said on its own straight and
  ends before its braking zone. They compete when a picked corner's own straight is too short (by the
  reference lap) to say its cue: then its cue may be said on the straight before the previous corner,
  still ending before that corner's braking zone. The picked corner wins that straight over a corner
  not picked, at focus and full verbosity alike; on the next lap, while the driver tries it, the other
  corner gets its straight back.

Without picks the coach behaves exactly as before.

Phrase lengths are estimated (0.2 s plus 0.4 s a word); the audio delay setting
covers Bluetooth lag. The simulated-session tests check that every cue is heard
outside every braking zone, with and without a 2 s delay.
