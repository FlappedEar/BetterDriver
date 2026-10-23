# FR-S reader (FBBD-319)

A separate test APK, not part of the BetterDriver app: it finds out which car data a 2013 Scion
FR-S gives through the Vgate vLinker MC+ (Bluetooth LE), so a later analysis decides which signals
the app adopts. It reuses the app's OBD-II code (`:obd`) and adapter link (`:core-ble`).

- **Run checks** (car standing, engine on, about six minutes): adapter identity, VIN (serial cut
  off), supported mode 01 PIDs with rate and latency of each, a six-PID request, trouble codes
  (read only, never cleared), the FR-S wish list requests (brake 7B0 21 41, oil 7E0 21 01, ATF
  7E1 21 82, accelerator, coolant, intake), a CAN survey of every id at the OBD port, each id
  listened to on its own with every frame, and a mode 21 sweep 00..FF of six ECUs.
- **Start drive log**: the wish list's CAN ids (0D0, 0D1, 140, 144, 360) round robin with every
  frame, and the wish list requests every 2 s, until **Stop**.
- **Share logs**: every `frs-reader-*.txt` through Android's share sheet.

The log is plain text, one line per event: `<ms since start> <kind> <text>`; the kinds are listed
in `ReaderLog`. No location is recorded. Only reads are sent to the car.

Published by `.github/workflows/canreader.yml` as the pre-release `canreader-vX.Y.Z` (versionName
in `build.gradle.kts`) on every change merged to main; the app's releases are not affected.
