# core-ble

RaceBox Mini / Mini S / Micro over Bluetooth LE (FBBD-8, FBBD-43, FBBD-44).

- `scanRaceBoxes(context)`: a Flow of RaceBoxes in range, strongest first. Used by
  the pairing screen.
- `RaceBoxSource(context, address, name)`: a recorder `DataSource`. Attach it to a
  `Recording`; it connects, reconnects on its own until stopped, and submits one
  sample set per RaceBox Data Message.
- `RaceBoxLink`: the connection manager behind the source. Connect, MTU 517,
  subscribe, reconnect after 0.5 s, 1 s, 2 s, then every 3 s; a link that is
  silent for 3 s, or sends nothing within 10 s of connecting, is restarted.
  State is a `StateFlow<LinkState>`.
- `UbxFrameReader`, `RaceBoxData`, `RaceBoxDecoder`: pure Kotlin, unit tested.

## Protocol

**Sources.** RaceBox publishes a BLE protocol document, but only by email after
accepting its terms ([request form](https://www.racebox.pro/products/mini-micro-protocol-documentation)),
so it has not been read for this code. Everything below comes from the
open-source client [lademeister/ESP32-RaceBox](https://github.com/lademeister/ESP32-RaceBox)
(`ESP32_RaceBox_BLE_client.ino`, GPL-3.0; facts only, no code copied), whose
comments cite the RaceBox datasheet. Check it against the official document
when Arek has it (FBBD-42).

**GATT.** Nordic UART Service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`; the device
notifies on TX `6E400003-…`, commands go to RX `6E400002-…`. No bonding.
Devices advertise a name starting with `RaceBox` (`RaceBox Micro <serial>`).
The same source lists an NMEA service `00001101-0000-1000-8000-00805F9B34FB`
(TX `00001103-…`), not used yet (FBBD-45).

**Framing (UBX).** `B5 62`, class, id, length (u16), payload, CK_A, CK_B.
The checksum is the 8-bit Fletcher sum over class, id, length and payload.
Payloads are at most 504 bytes. A packet can span several notifications.

**RaceBox Data Message** (class `FF`, id `01`, 80-byte payload, little-endian):

| Offset | Type | Field | Scale / unit |
| --- | --- | --- | --- |
| 0 | u32 | iTOW | ms |
| 4 | u16 | year | |
| 6 – 10 | u8 ×5 | month, day, hour, minute, second | UTC |
| 11 | u8 | validity flags | bit 0 date valid, bit 1 time valid (inferred, u-blox NAV-PVT) |
| 12 | u32 | time accuracy | ns |
| 16 | i32 | nanoseconds | ns, signed (inferred, u-blox NAV-PVT) |
| 20 | u8 | fix status | 3 = 3D fix (source); 0 none, 2 2D (inferred, u-blox NAV-PVT) |
| 21 | u8 | fix flags | bit 0 fix OK, bit 5 heading valid |
| 22 | u8 | date/time flags | |
| 23 | u8 | satellites | |
| 24 | i32 | longitude | 1e-7 deg |
| 28 | i32 | latitude | 1e-7 deg |
| 32 | i32 | WGS altitude | mm |
| 36 | i32 | MSL altitude | mm |
| 40 | u32 | horizontal accuracy | mm |
| 44 | u32 | vertical accuracy | mm |
| 48 | i32 | speed | mm/s |
| 52 | i32 | heading | 1e-5 deg |
| 56 | u32 | speed accuracy | mm/s |
| 60 | u32 | heading accuracy | 1e-5 deg |
| 64 | u16 | PDOP | 0.01 |
| 66 | u8 | lat/lon flags | bit 0 set = coordinates invalid |
| 67 | u8 | battery | Micro: input voltage × 10; Mini: % with bit 7 = charging |
| 68 | i16 ×3 | acceleration X, Y, Z | milli-g |
| 74 | i16 ×3 | rotation rate X, Y, Z | 0.01 deg/s |

Other messages of class `FF` (`21` history, `22` recording status, `23`
download, `26` recording state change) are ignored.

## Channels

Each data message becomes one sample set stamped with the recorder clock on
arrival. GNSS time goes in `gnss_utc_ms`. Position, speed, MSL altitude and
accuracies are only included while there is a fix (fix OK, type 2 to 4,
coordinates valid), so the VBO export never writes rows at 0°, 0°; heading
only while the heading is valid. Acceleration and rotation rate are always
included. RaceBox-only channels use ids 100 to 199 (`RaceBoxChannels`).
Fix changes and link drops are session events (`RaceBoxEvents`).

## Tests

The decoding tests use a payload built from the example bytes quoted in the
reference client, not a capture from our own Micro. Capture real packets
during the soak test (FBBD-46) and add them.
