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

When the session has video, the lap analysis also plays the realistic optimal lap as one video,
stitched from the laps it is made of (the raw theoretical best is there too). The plan's perfect
lap has its own video: the [perfect lap in 3D](getting-faster.md#perfect-lap-in-3d).

## Share a session to the tablet

Review is easiest on a tablet. To move a session there:

1. On the phone, in **Sessions**, open the session's **⋮** sheet and tap **Share**.
2. The app prepares the session as one file, with its video and its track, then opens Android's
   share sheet. Choose **Quick Share** and the tablet (or Files, Drive and the rest).
3. On the tablet, open the received file with BetterDriver, or use **Import session** in
   Sessions. The session and its track are added.

If Share cannot prepare the file (usually too little free space for the copy the share sheet
needs), **Save file instead** writes it straight to a file you pick, with no copy.

Tracks are shared the same way, from the track editor's **Share**.

## Backup

To move everything at once (tracks, equipment, cars, settings, coach references, optionally the
sessions), use **Back up** and **Restore backup** in **Settings › App**. See
[Sessions › Backup and restore](sessions.md#backup-and-restore).
