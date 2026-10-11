# gopro

Pure Kotlin/JVM GoPro support (FBBD-22). The Android BLE and Wi-Fi glue lives in
the app, so everything here is tested on the JVM. Nothing in the recorder uses it,
so a GoPro failure cannot affect data recording (FBBD-69).

| Camera | During the session | After the session |
| --- | --- | --- |
| HERO11 Black | `Hero11Source` starts and stops it over Open GoPro BLE | `GoProDownloader` fetches the files over the camera's Wi-Fi and aligns them |
| HERO8 Black | Started by hand (no Open GoPro support) | Files are copied into the session and `GoProImporter` aligns them |

Open GoPro supports the HERO11 from firmware v01.10.00; the HERO8 is not on its
list ([docs](https://gopro.github.io/OpenGoPro/docs)).

## HERO11 control

`OpenGoPro` holds the BLE protocol: packet framing (general, extended 13-bit and
16-bit headers, continuation packets, 20-byte packets) and the TLV commands used:
Set Shutter (0x01), Set Local Date Time (0x0F), Set AP Control (0x17), Get Hardware
Info (0x3C), status query 0x13 (busy = 8, encoding = 10) and the keep-alive
(setting 0x5B = 0x42, every 3 s like the Open GoPro Python SDK). Sources:
[BLE setup](https://gopro.github.io/OpenGoPro/ble/protocol/ble_setup.html),
[data protocol](https://gopro.github.io/OpenGoPro/ble/protocol/data_protocol.html),
[control](https://gopro.github.io/OpenGoPro/ble/features/control.html), and the
`open_gopro` 0.22.0 Python package for status ids, the keep-alive interval and the
hardware info layout.

`Hero11Source` is a `LiveVideoSource`. On start it connects, sets the camera's
clock from the phone, waits until the camera is not busy and sends Set Shutter on.
It notes the session time of the camera's acknowledgement in
`<session>/video/gopro-recordings.json`. While recording it keeps the link alive
and reconnects if it drops; on stop it sends Set Shutter off. It never throws and
reports failures through `state`.

## Download and alignment

After the session, `GoProDownloader` turns on the camera's access point over BLE,
reads its SSID and password, joins it (`WifiJoiner`, Android in the app), lists the
media at `http://10.5.5.9:8080/gopro/media/list`, downloads every chapter of the
recording made between the start and stop acknowledgements into `<session>/video`,
and imports them. It must not run while a session is recording.

`GoProImporter` reads each file's GPMF GPS (see `:video`) and aligns it to the
session's GNSS speed. Starting guesses, best first: the previous chapter's end, the
HERO11 start acknowledgement, then the file's creation time on the camera's clock.
Files that land outside the session are reported and not indexed.

## Not yet verified

Everything above is tested against synthetic files and a fake camera. Real HERO11
and HERO8 tests are in Jira for Arek.
