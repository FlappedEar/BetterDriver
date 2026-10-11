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

**Source.** RaceBox BLE Protocol Description, revision 8 (16 April 2024),
covering the Mini, Mini S and Micro on firmware 2.x and 3.x. RaceBox sends it by
email on request ([request form](https://www.racebox.pro/products/mini-micro-protocol-documentation));
Arek has a copy (FBBD-42); it is not committed here. Page
numbers below refer to it.

**GATT (p. 1–2).** UART service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`; the
device notifies on TX `6E400003-…`, commands go to RX `6E400002-…`. No bonding.
Names are `RaceBox Mini `, `RaceBox Mini S ` or `RaceBox Micro ` plus a 10-digit
serial. Firmware 3.3+ also has an NMEA service `00001101-0000-1000-8000-00805F9B34FB`
(TX `00001103-…`), not used yet (FBBD-45); RaceBox advises against using both
at once. The live data message is on as soon as notifications are enabled.

**Framing (UBX, p. 3).** `B5 62`, class, id, length (u16), payload, CK_A, CK_B.
The checksum is the 8-bit Fletcher sum over class, id, length and payload.
Payloads are at most 504 bytes. A packet can span several notifications, and
one notification can hold several packets.

**RaceBox Data Message** (p. 4–7; class `FF`, id `01`, 80-byte payload,
little-endian, up to 25 Hz, derived from u-blox NAV-PVT):

| Offset | Type | Field | Scale / unit |
| --- | --- | --- | --- |
| 0 | u32 | iTOW | ms |
| 4 | u16 | year | |
| 6 – 10 | u8 ×5 | month, day, hour, minute, second | UTC |
| 11 | u8 | validity flags | bit 0 date valid, bit 1 time valid, bit 2 fully resolved |
| 12 | u32 | time accuracy | ns |
| 16 | i32 | nanoseconds | ns, signed (can be negative) |
| 20 | u8 | fix status | 0 none, 2 2D, 3 3D |
| 21 | u8 | fix flags | bit 0 fix OK, bit 5 heading valid |
| 22 | u8 | date/time flags | bits 5–7: confirmation available, UTC date / time confirmed |
| 23 | u8 | satellites | |
| 24 | i32 | longitude | 1e-7 deg |
| 28 | i32 | latitude | 1e-7 deg |
| 32 | i32 | WGS altitude | mm |
| 36 | i32 | MSL altitude | mm |
| 40 | u32 | horizontal accuracy | mm |
| 44 | u32 | vertical accuracy | mm |
| 48 | i32 | speed | mm/s, ground speed (default config) |
| 52 | i32 | heading | 1e-5 deg, 0 = north |
| 56 | u32 | speed accuracy | mm/s |
| 60 | u32 | heading accuracy | 1e-5 deg |
| 64 | u16 | PDOP | 0.01 |
| 66 | u8 | lat/lon flags | bit 0 set = coordinates invalid |
| 67 | u8 | battery | Micro: input voltage × 10; Mini: % with bit 7 = charging |
| 68 | i16 ×3 | acceleration X, Y, Z | milli-g; X front/back, Y right/left, Z up/down |
| 74 | i16 ×3 | rotation rate X, Y, Z | 0.01 deg/s; X roll, Y pitch, Z yaw |

Other class `FF` messages (ACK `02`, NACK `03`, history `21`, recording status
`22`, download `23`, erase `24`, recording config `25`, state change `26`, GNSS
config `27`, unlock `30`) are ignored; the app sends no commands. Defaults per
p. 10: automotive platform model, ground speed, 3 m minimum horizontal accuracy
(worse than that clears the fix flag).

## Channels

Each data message becomes one sample set stamped with the recorder clock on
arrival. GNSS time goes in `gnss_utc_ms`. Position, speed, MSL altitude and
accuracies are only included while there is a fix (fix OK and fix status 3,
as RaceBox recommends on p. 5, and coordinates valid), so the VBO export never writes rows at 0°, 0°. Heading
(heading of motion) is included with every fix: the "valid heading" flag is u-blox's headVehValid,
which a RaceBox never sets because it does not run sensor fusion. Acceleration and rotation rate are always
included. RaceBox-only channels use ids 100 to 199 (`RaceBoxChannels`).
Fix changes and link drops are session events (`RaceBoxEvents`).

## Tests

The decoding tests use RaceBox's own example packet (p. 7, from a Mini) and
check its checksum. Note the document prints the example's rotation rates
without their sign; the fields are Int16, so `2F FF` is −2.09 deg/s. Capture
real packets from our Micro during the soak test (FBBD-46) and add them.
