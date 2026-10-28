# Sessions

**Sessions** lists every session on the device, one row each, grouped by day ("Today",
"Yesterday", "Sat 25 Oct").

Each row shows when and where ("16:23 · Silesia Ring"), then the car, the length, the timed laps
and what was recorded (**Video**, **OBD**, **HR**), with the best lap in purple. A row says when
something is running for it, for example "Exporting…" or "Getting GoPro video…", and marks a
session as **Recording**, **Recovered** or **Imported**.

- **Tap a row** to open its [lap analysis](analysis.md).
- **⋮** opens everything else for that session.

## Compare laps with video

**Compare laps** at the top of Sessions opens two laps side by side with their videos, from the
same session or two different ones. It opens on a picker: tap a lap for each side. The two players
follow one cursor along the lap and stop on the same frame when you pause. **Change laps** returns
to the picker.

## Track day

When you drove the same track in two or more sessions on one day, the day's heading has a
**Track day ›** link. The track day page reads the whole day as one; see
[Getting faster](getting-faster.md#track-day).

## The session menu (⋮)

⋮ on a row opens the session's sheet:

- **Video**: for a GoPro that starts with the session, **Download** its clips (keep the camera on
  and close to the phone). For a camera started by hand, **Add** its video files. Each camera
  shows how many clips were lined up and how, for example "by GPS ±0.05 s".
- **Watch video**: plays the session's whole video on the phone with the data band of
  [Video with data](export.md#video-with-data) over it: speed, G, the map with the car, and the
  lap and delta when the session has laps. A drive away from any saved track (a commute) plays
  too: the band then shows speed, G and the road you drove. See
  [Watch a session's video](#watch-a-sessions-video).
- **Coach review**: every cue of the session and what came of it; see
  [the spoken coach](coach.md#after-a-cue-did-it-work).
- **Delete**: asks first and says what is removed. Files still on a GoPro stay there.
- **Share**: sends the session as one file, with its video and its track. See
  [Export and sharing](export.md#share-a-session-to-the-tablet).
- **Export ▾**: VBO, CSV, GPX and a video with data. See [Export and sharing](export.md).

## Watch a session's video

**Watch video** in the session's sheet opens its video from the start, paused. **Play** and
**Pause** start and stop it; drag the bar under it to go anywhere in the video. Every clip of the
session plays one after the other. Nothing is rendered or saved: to keep an MP4, use
[Video with data](export.md#video-with-data).

!!! note "Not released yet"
    Merged after 1.14.0. It ships in the next release.

## Import a session

**Import session** at the top of Sessions takes files from other apps and devices:

- a BetterDriver session file (`.bdsession`) shared from another phone or tablet,
- VBO files (from RaceChrono, Circuit Tools and other loggers; a RaceChrono VBO's OBD pedals and heart rate are read too),
- GPX,
- RaceChrono CSV.

One pick can take several files; they are imported oldest session first. The import card shows
the file's name, how much is read, and the time left; a file that fails does not stop the rest,
and the end lists each result. Imported sessions take part in the analysis and the progression,
marked with their origin.

## Backup and restore

**Settings › App** has **Back up** and **Restore backup**.

- **Back up** writes tracks, equipment and cars, app and coach settings and coach references to a
  file. **Include sessions (without video)** adds the sessions.
- **Restore backup** offers **Merge**, which keeps everything on this device and adds what it
  lacks, or **Replace…**, which replaces it with the backup and cannot be undone.

Recording is blocked while a restore runs.
