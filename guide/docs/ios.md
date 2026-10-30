# iPhone and iPad

The iPhone and iPad app is the same BetterDriver: the same screens, tabs and names in the same
order, and the same timing, analysis and coach, because both apps run one shared core. It comes
through TestFlight (see [Install](getting-started.md#install)) and carries the Android release's
version number. Session files (`.bdsession`) move between the two in both directions.

## Who does what

- **iPhone**: records, like an Android phone: the track screen, live laps, the spoken coach,
  video, car data and heart rate. Needs an iPhone 12 or newer with iOS 17 or later.
- **iPad**: analysis only, like an Android tablet. It has **Sessions**, **Car** and **Settings**
  but no **Drive**; the session list stays beside the session you read.

## What works on the iPhone

- Receivers: a RaceBox (Mini, Mini S, Micro) over Bluetooth LE, or the phone's own GPS.
- OBD-II over Bluetooth LE (for example the Vgate vLinker MC+ or a Konnwei adapter) and over
  Wi-Fi, the FR-S CAN channels, the car page with **Scan car** and your own parameters.
- A Bluetooth heart-rate strap.
- The phone camera, always recorded in landscape; GoPro files lined up by the GoPro's GPS, and
  GoPro start and stop with the session.
- Lap analysis with every tab, Where to find time and coach corner flags, Compare laps from any
  sessions, Every pass, Progression, Coach review, track day, the perfect lap in 3D, Watch video,
  Video with data, VBO, CSV and GPX export, and the track editor with corner corrections and
  **Make a track from this session**.

## Not on iOS yet

| Missing on iPhone and iPad | On Android |
| --- | --- |
| Other GNSS receivers (NMEA over Bluetooth or Wi-Fi, BLE LNS, VBOX Sport, Dragy, RaceChrono DIY) | [Equipment](equipment.md) |
| Bluetooth Classic receivers and OBD-II adapters | iOS does not allow them |
| **Demo track** and the built-in replay drives | [Try it without a track](demo.md) |
| Backup and restore; sharing tracks between devices | [Export and sharing](export.md#backup) |
| The **Data quality** card and the coaching history in the analysis | [Lap analysis](analysis.md#summary) |
| This session against earlier sessions on the Summary | [Lap analysis](analysis.md#summary) |
| The optimal-lap composite video | [Export and sharing](export.md) |
| GoPro download over Wi-Fi | [Equipment](equipment.md) |
| RaceBox diagnostics, the camera test and **Run setup again** (shown as "Not implemented yet") | [Settings](settings.md) |
| In-app updates | TestFlight updates the app instead |

The list is kept with the code: every feature of both apps, done or still missing, is in
[docs/parity.md](https://github.com/FlappedEar/BetterDriver/blob/main/docs/parity.md).
