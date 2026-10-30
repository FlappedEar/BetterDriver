# Install and first setup

## Install

**Android.** BetterDriver is published as an APK on
[GitHub Releases](https://github.com/FlappedEar/BetterDriver/releases/latest).

1. On the phone, open the latest release and download `BetterDriver-X.Y.Z-android.apk`.
2. Open the file and allow your browser or file manager to install apps if Android asks.
3. Do the same on the tablet if you review sessions there.

Each release also lists `SHA256SUMS.txt` to check the download.

**iPhone and iPad.** The app comes through Apple's TestFlight, by invitation. Install TestFlight,
accept the invitation and install BetterDriver; TestFlight offers each new build. It needs iOS 17
or later and an iPhone 12 or newer. See [iPhone and iPad](ios.md) for what differs from Android.

### Updates

On the iPhone and iPad, TestFlight installs updates. On Android, the app checks GitHub Releases for a newer version. When one is waiting, a small dot appears on
**Settings** in the bottom bar. In **Settings › App**, tap **Check for updates**; a newer version
shows **Download**, then **Install** once it is downloaded. While a session records, the row says
**After the session**.

## First setup

The first start walks you through a short setup. You can run it again at any time from
**Settings › App › Run setup again**.

**Permissions.** Location and nearby devices are needed for lap timing and Bluetooth equipment.
Camera, microphone and notifications are optional: without camera access the phone camera does
not record. **Settings › App** lists every permission that is off and what it stops.

**GNSS receiver.** Lap timing needs one. Pick a RaceBox found nearby, another receiver, or the
phone's own GPS; see [Equipment](equipment.md). To try the app first, choose a demo receiver
([Try it without a track](demo.md)).

**Car.** Optional. Its name goes into each session and its export, and it is where the car's live
values show. See [Car](car.md).

**Cameras.** Optional. The phone camera is on by default; you can add a GoPro or another camera.

## Find your way around

The bottom bar (on an Android tablet, the rail down the side) has four places. A tablet (an iPad,
or an Android tablet) is for analysis only, so it has no **Drive** and opens on **Sessions**.

!!! note "Not released yet"
    An Android tablet without **Drive** is merged after 1.21.0. It ships in the next release; the
    iPad has it from TestFlight build 1448.


| Item | What it holds |
| --- | --- |
| **Drive** | Start a session, with the session's car, receiver and cameras. See [Drive and tracks](drive.md). |
| **Sessions** | Every session, grouped by day; tap one for its lap analysis. See [Sessions](sessions.md). |
| **Car** | The next session's car and its live values. See [Car](car.md). |
| **Settings** | One page of topics. See [Settings](settings.md). |

The app is always dark, with a true black background: on an OLED screen a black pixel is off.
