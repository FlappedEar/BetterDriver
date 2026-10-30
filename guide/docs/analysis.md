# Lap analysis

Tap a session in **Sessions** to open its lap analysis. Six tabs run along the top (the row
scrolls sideways on a phone): **Summary**, **Compare**, **Map**, **Laps**, **Corners** and
**Car**. On the iPhone and iPad a session with video has a seventh tab, **Video**, after **Car**
(Watch video and Save video in one place); on Android these stay in the session's sheet. On a
tablet the session list stays beside the analysis, and turning the tablet keeps
your tab, compared laps, map view, corner and scroll position.

## Where the numbers come from

- A value that is not measured (calculated, inferred or simulated) is marked **≈**.
- Every corner pass, lap and conclusion has a confidence level: **High**, **Medium** or **Low**,
  with the reason it is not higher (a GPS gap, one lap only and so on). A number is shown only
  when everything behind it is High confidence and measured; otherwise the same advice is given in
  words, or "–".
- A lap with a GPS gap still shows its time but is never the best lap, the delta reference or a
  coach target.

## A session without laps

A session with no laps (driven away from any saved track, a commute for example) keeps the same six
tabs. Summary says there are no laps at the top, and under that shows the drive: distance, time,
average speed while moving and top speed, the route on the map coloured by speed (touch it to read a
point), and the speed along the drive. Map and chart share one cursor. Car has the pedals,
temperatures and heart rate of the whole drive; the tabs that need laps say so.

The bar at the top shows when the session started and its track. Its menu opens the track's
progression and the coach review.

## Summary

The Summary opens with what to do next and what came of last time:

- **Next session · work on**: one main focus corner and at most two more, each with the time to
  gain and what your faster passes did differently (braking point, minimum speed, exit speed).
  "≈ N s ›" opens [Where to find time](getting-faster.md#where-to-find-time).
- **Last focus · what came of it**: whether the coach worked on the last focus, whether the
  braking point or speeds moved the way it asked, and the time won back. Earlier gains are marked
  **Learned**, **Partly held** or **Lost** in later sessions.
- **Three lap times**, each named for what it is:
    - **Best actual lap**: your fastest real lap.
    - **Realistic optimal**: the fastest lap you have shown you can combine. Pieces of different
      laps are joined only where they match (speed, line, heading, G).
    - **Raw theoretical best**: the sum of each segment's fastest time.
- The **typical (median) lap** and the lap spread.
- **Where time goes**: the corners where the most time was lost, and on which lap each corner's
  best was set.
- **Heart rate** (when a strap was worn): average, highest and lowest.
- **G-forces · whole session**: the G-G diagram and peaks.
- **Data quality**: GNSS rate, gaps, video coverage and the other recording checks, and where each
  channel came from. With OBD-II, its own part: link drops, the longest gap and the time without
  car data.
- **Progress over every session here**: opens the [progression](getting-faster.md#progress-over-every-session).

## Compare

Pick two laps to compare them along the lap: time delta, speed, and the driving state at each
point (braking, coasting, cornering, accelerating, full throttle). To watch two laps' videos side
by side, use **Compare laps** in [Sessions](sessions.md#compare-laps-with-video).

## Map

Two views:

- **Laps**: the laps drawn on the track.
- **Where to find time**: the line plan, ranked by corner. See
  [Getting faster](getting-faster.md#where-to-find-time).

## Laps

Every lap with its time, and with heart rate per lap (average · highest) when it was recorded. Tap
a lap to compare it.

## Corners

Every corner with its time, braking point, minimum and exit speed, coasting and the spread
between passes. On a corner:

- **Every pass** opens each pass through that corner with its speed trace (brake and throttle too
  when the car sent them). Where the session has video covering the pass, it plays from just
  before the corner, and two passes can be compared side by side.
- **Coach this next time** picks the corner for the spoken coach's next session at this track.

## Car

Only what the car and your sensors sent; a channel that was not recorded shows as not recorded,
never as zero.

- **Pedals**: a bar per lap with the share of full throttle, part throttle, coasting and braking.
  With brake pressure (FR-S over CAN), the hardest brake, the typical stop and the seconds per lap
  at half braking or more, in bar.
- **Steering · FR-S**: most lock each way, seconds per lap over 90° and the best lap's wheel trace.
- **Temperatures**: the lowest and highest of each car temperature per lap.
- **Heart rate** over the session, with the laps shaded.

A road drive without laps shows the same cards for the whole drive.
