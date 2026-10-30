# Drive and tracks

## The Drive screen

**Drive** is where a session starts.

- At the top, the session's devices as chips.
- In the middle, the big **START** ring.
- Under it, the session's choice in one line, for example "Corolla · RaceBox Micro 3241" and
  "2 of 3 cameras on". **Change** opens a sheet with the car, the receiver and a switch for each
  camera.
- The track the app detected from your position, or **No track detected**.

Tap **START** to record. The app switches to the [track screen](track-screen.md). While a session
records, its choice is fixed until it stops.

Without a receiver the ring says **No GNSS receiver**: add one with **Add receiver**, or a demo
receiver to try the app.

!!! tip "Before you go out"
    Mount the phone, plug it in, connect the helmet headset and switch the car's ignition on if
    you use an OBD-II adapter. **Settings › Equipment** shows which devices are **In range**.

## Stopping

On the track screen, hold your finger anywhere for **3 seconds**: a ring fills, and lifting
earlier cancels. A swipe never stops a session.

The session is saved as it records, so a crash or a killed app loses nothing already recorded; such
a session shows as **Recovered** in Sessions.

## Tracks

Lap timing, sectors and the coach need a saved track with a start/finish line. Open
**Settings › Tracks**.

- **Where you are** lists circuits near you from the built-in circuit list. Pick one to save it.
- **New track here** makes a track at your position with a name you type, for a circuit that is
  not on the list.
- **My tracks** lists your saved tracks. **Edit lines** opens the track editor.
- **Import** takes a track file shared from another phone or tablet.

A session recorded before its track was saved still counts: the app finds its laps once the
track exists.

At a place with no start/finish line yet (a new track, or one saved without its line), the app
places a provisional line by itself once you have driven the same loop about one and a half
times: across the middle of the longest straight, the way you drive. Lap times and the delta
appear on the track screen from then on, and the laps before it are timed too. The line is not
saved: after the session, **Make a track from this session** in the analysis lets you check and
save it. A loop has to be 0.8 to 30 km long, so an ordinary road drive gets no line.

### The track editor

The editor shows your recorded lap on a map.

- **Start / finish**: tap your lap where the line goes; it is placed square to the way you drove,
  30 m wide. Drag its ends to the track edges. The arrow shows the direction; crossings the other
  way never count. **Flip direction** turns it round.
- **+ Add sector line** adds sector lines. **Pit entry** and **Pit exit** lines let the coach go
  quiet in the pit lane.
- **Point to point**: for hill climbs and sprints, runs go from a start line to a finish line;
  the drive back is not timed.
- **Corners**: found from your laps once a session at the track has been analysed. Tap one to
  **Rename**, move it, **Split**, **Merge with next**, **Remove** or change its **Kind** (corner,
  chicane, straight). **Restore automatic** undoes your corrections. Corrections are used for
  every session at the track, so "Turn 3" means the same corner in the analysis, the coach and
  the video.
- **Share** sends the track to another device.

### Satellite map

Without a key the editor shows OpenStreetMap. To see satellite imagery, tap **Map** at the bottom
right of the map, choose **Add MapTiler key…** and paste your own [MapTiler](https://www.maptiler.com/)
key. The key stays on the device; add it once on the phone and once on the tablet. The same key
draws the ground under the [perfect lap in 3D](getting-faster.md#perfect-lap-in-3d).
