# recorder

Recording service core and crash-safe session store. Pure Kotlin/JVM: it depends
only on data sources and the session store, never on coach, analysis, camera or
UI code (FBBD-69 to FBBD-72). The Android foreground service that hosts it in its
own process lives in the app.

Arek's requirement (2026-10-11): if the coach or any other part fails, the session
must still hold all recorded data and export as VBO, as RaceChrono does.

## How data survives a failure

- **Append as it arrives.** Each sample set is a framed record appended to the
  current chunk file. There is no "save at end" step.
- **Flush and sync.** The writer hands data to the OS whenever its queue is
  empty and at least every 100 ms, so killing the process loses (almost)
  nothing. A separate thread fsyncs every 500 ms, so a power loss loses at most
  about the last second. fsync runs off the writing thread so a slow disk never
  holds up writing.
- **Queue.** Samples wait in a bounded in-memory queue (100k sets) until the
  writer takes them; the loss limit above assumes the writer keeps up. On a
  desktop JVM it wrote about 400k sets/s (not yet measured on the phone), so
  25 Hz GNSS never builds a backlog. If the queue is full, new samples are
  dropped and counted in `dropped_samples`.
- **Metadata first.** `meta.properties` (start time, car, track, devices,
  start/finish gate, known channels) is written atomically before any sample.
- **Isolated consumers.** Live consumers (`RecorderListener`) run on their own
  thread. A consumer that throws is logged as a `listener_error` event; one
  that stalls loses its oldest updates. Neither affects what is written. A data
  source that fails to start is logged as `source_error`.
- **Storage.** Below 200 MB free the recorder logs `storage_low` and tells
  listeners; below 50 MB it stops cleanly with the data intact. Records already
  queued are written first, unless they would eat into the space kept for the
  end record; any left over are counted in `droppedSamples`.

## Session directory

```
meta.properties      written atomically before the first sample
chunk-000001.bdr     append-only framed records, rotated at 8 MB or 60 s
session.lock         held by the recording process while it records
finished.properties  written atomically after a clean stop
recovery.properties  written by recovery; chunk files are never changed
```

Frame layout (big-endian): magic `BDR1` (u32), type (u8), payload length
(u32), payload, CRC-32 over type, length and payload. Record types: channel
definition, sample set (session-time microseconds plus channel id/value pairs),
event, end. Every chunk starts with the full channel table, so each chunk can be
read on its own.

## Recovery

`SessionStore.recoverInterrupted()` runs at app start. A session with no
`finished.properties` whose lock nobody holds was interrupted. Reading stops at
an incomplete last frame (a crash mid-write) and skips damaged frames by
searching for the next magic number. Recovery writes `recovery.properties` and
the session then lists as `RECOVERED` and exports like any other.

## VBO export

`VboExporter` writes Racelogic's VBO text format
([structure](https://developers.racelogic.co.uk/Portal/Tutorials/tutorialVBOfilestructure.html)):
one row per GNSS fix, standard channels (sats, time, lat, long, velocity,
heading, height) plus any other channel, sample-and-hold. Latitude and longitude
are minutes, longitude positive west; time is UTC `hhmmss.sss`. The start/finish
gate goes in `[laptiming]`. Not yet verified in Circuit Tools or RaceChrono
(FBBD-68).

## Live values

`LiveTracker` is a `RecorderListener` that keeps what the track screen needs
(recording time, GNSS rate, satellites, speed, storage warnings) as a small
`LiveSnapshot`. Like any listener it runs on the listener thread, so it cannot
affect the recording.

## In the app

`RecorderService` (app, `recording/`) runs the recorder as a foreground service
in its own process (`:recorder`) with a partial wake lock. Its foreground service
types are location and connected device (FBBD-34), each claimed only while Android
accepts it (`recorderForegroundTypes`): on Android 14 location needs location
access and connected device needs nearby devices, so startForeground never throws
for a denied permission. The UI process starts
and stops it with intents and receives `LiveSnapshot`s over a Messenger. Killing
or crashing the UI leaves the recording running; reopening the app reconnects and
returns to the track screen. While a recording runs, the app keeps the screen on.
At app start, and whenever the recorder process ends, the UI process runs
`recoverInterrupted()`. Sessions are listed on the Sessions tab and exported as
VBO through Android's save dialog.

## Fakes

`FakeGnss` / `FakeGnssSource` produce a deterministic 25 Hz lap around a
circle, for tests and the alpha build without a RaceBox. OBD is deferred.

## Tests

`./gradlew :recorder:test`. `FaultInjectionTest` (FBBD-72) starts a child JVM
recording fake GNSS and SIGKILLs it at 20 random points; each recovered session
must hold every fix up to at most 1 s before the kill and export a valid VBO.
Skip it locally with `-PskipFaultInjection`.
