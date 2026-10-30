# Export and sharing

## Export ▾

In **Sessions**, open a session's **⋮** sheet and tap **Export ▾**. Not while the session records.

| Choice | What you get |
| --- | --- |
| **Video with data** | An MP4 of one lap or the whole session with speed, times, G and the map drawn on the video. Needs video in the session. |
| **VBO + video** | For Circuit Tools: the VBO and its MP4 files, saved into a new folder. Offered when the session has video. |
| **VBO** | One VBO file with every data channel, no video columns. Opens in Circuit Tools and other VBO tools; G columns are included for FlappedEar Overlays. |
| **CSV** | Every channel and lap, for spreadsheets. Its time column follows the receiver's GNSS clock, so it lines up with RaceChrono. |
| **GPX** | The GPS track by lap, for maps. |

Android asks where to save the file. Car data channels (`obd_rpm` and the rest) and G columns
go into the VBO and CSV.

### Video with data

Pick **Whole session** or a lap from the list; the app renders the MP4 on the phone. The row in
Sessions says "Rendering video with data…" while it runs, and the session cannot be changed
meanwhile; **Cancel video** stops it.

A lap's video starts a few seconds before the start/finish line and ends as long after
(5 to 8 s, 6 to start with; pick under **Seconds before and after the lap**), as FlappedEar
Overlays exports one lap. Its lap time shows 0:00 until the line, runs during the lap, and
keeps the final time and the gap to your best lap after it.

    The video uses the **Tech HUD** layout of FlappedEar Overlays, so a video from the phone looks like
    one made in Overlays. Along the bottom: the RPM gauge with the speed in it, the lap (lap number,
    running time, best lap and the delta to it), throttle and brake, OIL, ATF and COOLANT with their
    ranges, tyres and the steering wheel. Top left the track map with the lap's trail and the car, top
    right the heart rate, bottom right the G radar with its last second and the G numbers. A widget
    whose value the session did not record (no OBD adapter, no heart-rate strap, no tyre sensors, no
    steering angle) is left out. **Watch video** shows the same.

When the session has video, the lap analysis also plays the realistic optimal lap as one video,
stitched from the laps it is made of (the raw theoretical best is there too). The plan's perfect
lap has its own video: the [perfect lap in 3D](getting-faster.md#perfect-lap-in-3d).

## Share a session to the tablet

Review is easiest on a tablet (an Android tablet or an iPad). A `.bdsession` file is the same on
Android and iOS, so a session recorded on either phone opens on either tablet. To move a session
there:

1. On the phone, in **Sessions**, open the session's **⋮** sheet and tap **Share**.
2. The app prepares the session as one file, with its video and its track, then opens the share
   sheet. On Android choose **Quick Share** and the tablet (or Files, Drive and the rest); on the
   iPhone choose AirDrop and the iPad, or Files.
3. On the tablet, open the received file with BetterDriver, or use **Import session** in
   Sessions. The session and its track are added.

If Share cannot prepare the file (usually too little free space for the copy the share sheet
needs), **Save file instead** writes it straight to a file you pick, with no copy.

Tracks are shared the same way, from the track editor's **Share**.

## Backup

To move everything at once (tracks, equipment, cars, settings, coach references, optionally the
sessions), use **Back up** and **Restore backup** in **Settings › App**. See
[Sessions › Backup and restore](sessions.md#backup-and-restore).
