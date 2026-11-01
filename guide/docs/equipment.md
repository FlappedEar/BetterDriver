# Equipment

**Settings › Equipment** lists your devices grouped by kind: GNSS receivers, cameras, sensors and
car data. While the page is open it looks for Bluetooth devices and says whether each one can be
reached now:

| State | Meaning |
| --- | --- |
| **In range** / **Not seen** | A Bluetooth LE device the page heard, or did not. |
| **Paired** | A Bluetooth Classic device; a scan never hears one, so it is only known as paired. |
| **Built in** | Part of the phone, such as the phone camera. |
| **Over Wi-Fi** | A Wi-Fi device; it is not looked for. |
| **Start by hand** | A camera you start yourself and whose files you add afterwards. |
| **Made-up data** | A demo receiver. |
| **In this session** | A session is recording and holds the connection, so the page does not look. |

Tap an item to rename it, choose its **Video** mode, open RaceBox **Diagnostics**, or remove it.

## Add equipment

**Add equipment** groups what can be added:

- **Found nearby**: Bluetooth LE devices heard now, such as a RaceBox, an Open GoPro camera, a
  Bluetooth LE OBD-II adapter, a heart-rate strap or the tyre pressure bridge. Tap **Add**.
- **Paired Bluetooth devices**: Bluetooth Classic devices already paired in Android's Bluetooth
  settings (GPS receivers, VBOX Sport, OBD-II adapters). Pair them there first.
- **Add without Bluetooth**: the phone's own GPS, a Wi-Fi receiver, the Wi-Fi OBD-II adapter,
  the phone camera, and cameras whose files you add after the session.
- **Try the app**: the demo receivers ([Try it without a track](demo.md)).

## GNSS receivers

| Receiver | Notes |
| --- | --- |
| **RaceBox** Mini, Mini S, Micro | The main receiver: 25 positions a second and exact lap times. Its accelerometer and the phone's are combined for G. |
| **Bluetooth GPS** | NMEA receivers over Bluetooth Classic, for example Qstarz or Garmin GLO. |
| **Bluetooth LE GPS** | Receivers with the standard Location and Navigation service. |
| **RaceChrono DIY GPS** | The open RaceChrono DIY device. |
| **Wi-Fi GPS** | NMEA or RaceChrono DIY over Wi-Fi. |
| **VBOX Sport** | Racelogic VBOX Sport. |
| **Dragy** | Dragy Pro / DRG70. |
| **Phone GPS** | The phone's own GPS, about one position a second. Fine for trying the app or a road drive; lap times are less exact. |

## Cameras

- **Phone camera**: records with each session it is turned on for. Pick the mode with **Video**:
  only modes the phone supports are offered, from the sharpest (4K · 60, most storage) to the
  smallest files. Video keeps its mode for the whole session; data recording never stops. 4K · 60
  uses about 36 GB an hour, 1080p · 60 about a quarter of that.
- **GoPro** (Open GoPro over Bluetooth): starts and stops with each session it is turned on for.
  After the session, download its clips from the session's ⋮ sheet; they are lined up with the
  data by GPS.
- **GoPro, files added after the session** (HERO8 or older, MAX) and **Other action camera** (DJI
  Action, Insta360, Garmin and others): start it by hand, then add its MP4 files to the session.
  They are lined up by the camera's clock.

Turn cameras on or off for a session with **Change** on the Drive screen.

## Sensors

**Heart-rate monitor**: any standard Bluetooth heart-rate strap. Its heart rate shows on the track
screen and in the analysis. A strap that is not worn at the start of a session is switched off
for that session, so it never shows a made-up heart rate.

**Tyre pressure bridge**: the FlappedEar TPMS bridge
([RCTPMS](https://github.com/FlappedEar/RCTPMS)), which hears the four Bluetooth tyre sensors and
shows up nearby as "RC TPMS ...". While it is listed, every session records each tyre's pressure
(bar) and temperature (°C) about once a second, on Android and on the iPhone. The values go into
the VBO export as `tyre_pressure_fl` to `tyre_pressure_rr` and `tyre_temp_fl` to `tyre_temp_rr`,
and the video's Tyres widget shows them. A wheel whose reading has not changed for 5 minutes
(a sensor that went quiet) is left out until it changes again. If the bridge drops, the session
and the GPS go on and the tyres come back when it reconnects.

## OBD-II adapters

- **OBD-II adapter** over Bluetooth Classic or Bluetooth LE, for example the Vgate vLinker MC+
  and other ELM327/STN adapters. Pair a Bluetooth Classic adapter in Android's Bluetooth settings
  first. A Bluetooth LE adapter with a name the app does not know (Konnwei KW902/KW903, LELink
  and others) is offered in **Add equipment** when it advertises an ELM327 serial service.
- **Wi-Fi OBD-II adapter** (192.168.0.10 : 35000): join its Wi-Fi network on the phone; if
  Android says the network has no internet, choose to stay connected.

If the adapter drops during a drive, the app tries again every 2 seconds for 2 minutes, so car
data comes back within seconds. A request the car stops answering is tried again after 30
seconds, then less often, up to every 5 minutes, so the other values keep updating.

On the iPhone, OBD-II works over Bluetooth LE and Wi-Fi; Bluetooth Classic adapters cannot connect
to an iPhone.

See [Car](car.md) for what is read and how to check it before driving.
