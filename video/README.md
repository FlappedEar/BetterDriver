# video

Pure Kotlin/JVM: the pieces of video support that every camera shares. No Android
dependencies, so it is tested on the JVM. The recorder never depends on it, so a
camera failure cannot reach the session data (FBBD-69).

## Video sources

`LiveVideoSource` is a camera that records with a session: the phone camera
(FBBD-21) or a GoPro HERO11 (FBBD-22). Its contract:

- It runs in the UI process, never in the recorder's process, and never calls the
  recorder.
- `start` and `stop` never throw. A failure shows in `state` as `Failed`, and the
  session goes on.
- `stop` returns the clips recorded so far, even after a failure.

A GoPro HERO8 has no remote control (it is not supported by Open GoPro), so it is
not a live source: its files are imported after the session and aligned.

## Clips and session time

A `VideoClip` is one video file tied to one session. Its `sessionOffsetUs` is the
session time of the clip's first frame, so the session time of any frame is
`sessionOffsetUs + videoTimeUs`. It is negative when the camera started before the
session. GoPro chapter files are separate clips, each with its own offset.

`alignment` says how the offset was found and roughly how far off it may be:
`CAPTURE_CLOCK` (phone frames stamped on the session clock), `GPS_MATCH` (GoPro GPS
track matched against the session's GNSS), `GPS_UTC`, `COMMAND_ACK`,
`CAMERA_CLOCK` or `MANUAL`.

## Session layout

```
<session>/video/clips.json   the clip index (VideoIndex)
<session>/video/*.mp4        the clips
```

`clips.json` is written atomically (synced temporary file, then rename) and is
separate from the recorder's files. An index that cannot be read is moved aside,
never overwritten.
