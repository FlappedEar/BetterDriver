# What's new

The full notes of every release are on
[GitHub Releases](https://github.com/FlappedEar/BetterDriver/releases) and in
[docs/release-notes](https://github.com/FlappedEar/BetterDriver/tree/main/docs/release-notes).

## 1.14.0

- **Beyond your best**: where your best lap already matches its target, *Where to find time* lists
  the braking points you can move beyond your own best ("Brake up to 18 m later · start with
  10"), they fill free focus places, and the coach asks for the step.
  See [Getting faster](getting-faster.md#where-to-find-time).
- **Gear in the corner**: from engine speed, the app works out the car's gears from its own
  sessions. Where a lower gear pulls clearly harder at the apex speed, the Map tab says "2nd at
  the apex" with the reason, and the coach adds the gear to the brake cue ("Turn 7, brake at 80,
  second"). See [The spoken coach](coach.md#what-the-cues-sound-like).

## 1.13.0

- On the FR-S the coach says what the steering wheel did and why it cost time ("front slid,
  slower in", "ran wide, turn in more"), and the Map tab shows the wheel advice with your wheel
  trace against the target's.
- The analysis **Car** tab adds a **Steering · FR-S** card, and the pedal card adds brake
  pressure in bar.
- The perfect lap in 3D fills the picture, and pinch moves the camera nearer or farther.
- The CSV export's time column follows the receiver's GNSS clock, so it lines up with RaceChrono.

## 1.12.0

- A **G radar** on the track screen's second screen.
- On a gen-1 FR-S, car data comes from the car's own CAN bus at 20 to 100 times a second.
- Lap analysis on the tablet keeps its place when the tablet turns.

## 1.11.0

- **Track day**: a whole day at one track read as one.
- The perfect lap in 3D follows the car, over a satellite photo with a MapTiler key.
- Import shows its progress and takes several files at once.

## 1.10.0

- The getting-faster loop: **Where to find time**, the coach aiming at the line plan with line
  and throttle cues, the next session showing what was won back, and the perfect lap in 3D.
- A third track screen with the map of the next corner.
