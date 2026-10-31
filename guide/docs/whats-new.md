# What's new

The full notes of every release are on
[GitHub Releases](https://github.com/FlappedEar/BetterDriver/releases) and in
[docs/release-notes](https://github.com/FlappedEar/BetterDriver/tree/main/docs/release-notes).

## iPhone and iPad (TestFlight)

- 30 Oct: Progression at a track, Coach review, Every pass, the Map tab's Laps view, Compare laps
  from any sessions, driving-state strips, corner corrections and **Make a track from this
  session**; coach corner flags in Where time goes; a smooth track screen through long
  recordings; Share with video no longer runs out of memory; phone video always landscape and a
  **Video** tab in a session; the iPad laid out as an Android tablet, without **Drive**.
  See [iPhone and iPad](ios.md).

## 1.24.0

- An Android session with video gets a **Video** tab after **Car**, as on the iPhone and iPad:
  pick the whole session, a composite lap or a lap, then **Watch** or **Save video**. See
  [the Video tab](analysis.md#the-video-tab).
- iPhone: **Demo track** and the built-in replay drives, the same laps as Android. See
  [Try it without a track](demo.md).
- iPhone and iPad: **Last focus · what came of it** on the Summary, as on Android. See
  [Getting faster](getting-faster.md).
- A heart-rate strap and a RaceBox reconnect on the iPhone as on Android.

## 1.23.0

- **Gear** can be picked as a track screen tile (Settings › Track screen), worked out from RPM and
  speed; a dash while it cannot be read. See [Track screen](track-screen.md).
- The video's RPM gauge shows the gear large, with the speed small under it and no RPM number,
  on Android and the iPhone. See [Export](export.md).

## 1.22.1

- A session recorded at 1 Hz (phone GPS) no longer shows a raw theoretical best or realistic
  optimal slower than its best lap; when the segments cannot add up to one, they are not shown.
- **Settings › Diagnostics › Share log** is checked by the app's tests on every build, on Android
  and on the iPhone and iPad. See [Settings](settings.md).

## 1.22.0

- A session with no laps, such as a road drive, keeps the six analysis tabs: Summary has the drive,
  G-forces, acceleration runs and data quality, Car the whole drive's car values, and the tabs that
  need laps say so. The bar shows the session's start time and track, and its menu opens the
  track's progression and the coach review.
- An Android tablet is for analysis only: it has no **Drive** and opens on **Sessions**, as the
  iPad. See [Getting started](getting-started.md).

## 1.21.0

- A Bluetooth LE OBD-II adapter with a name the app does not know is offered in **Add equipment**
  when it advertises an ELM327 serial service. See [Equipment](equipment.md#obd-ii-adapters).
- A recording whose start fails part-way stops cleanly; a failed video write is reported at once;
  a bundle or backup holding the same file twice is refused.
- The iPhone and iPad catch up with Android's session review (above).

## 1.20.2

- OBD temperatures keep updating for the whole session: a request the car stops answering is tried
  again after 30 s, then less often.

## 1.20.1

- A smoother track screen in the first seconds of a session.

## 1.20.0

- **Live laps at a new track**: with no saved start/finish, the app places a provisional line once
  you have driven the loop about one and a half times, then times laps, sectors and delta live.
  See [Drive and tracks](drive.md).

## 1.19.0

- Car data comes back within seconds after the OBD-II adapter drops mid-drive, and the session's
  **Data quality** card has an OBD-II part.

## 1.18.0

- **Video with data** and **Watch video** draw FlappedEar Overlays' Tech HUD layout. See
  [Export and sharing](export.md#video-with-data).

## 1.17.0

- The **Car** tab is the car's own page, with **Scan car** and your own OBD-II and CAN parameters.
  See [Car](car.md).
- A lap's video with data starts and ends a few seconds around the lap.

## 1.16.0

- The track screen's tiles are set in **Settings › Track screen**, one layout per car. See
  [The track screen](track-screen.md#choosing-what-a-tile-shows).

## 1.15.0

- **Watch video**: a session's sheet plays its video with the data band over it (speed, G, the
  map with the car, lap and delta where there are laps), also for a drive away from any track.
  See [Watch a session's video](sessions.md#watch-a-sessions-video).
- **G with Phone GPS**: the G radar and G tile now work with the phone's own accelerometer, in
  any mount position. See [The track screen](track-screen.md).
- **Drive without laps**: a session with no laps shows its route coloured by speed, the speed
  along the drive, distance, time, and average and top speed.
  See [Lap analysis](analysis.md#a-session-without-laps).

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
