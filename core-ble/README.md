# core-ble

RaceBox Mini / Mini S / Micro over Bluetooth LE (FBBD-8, FBBD-43, FBBD-44).

- `scanRaceBoxes(context)`: a Flow of RaceBoxes in range, strongest first. Used by
  the pairing screen.
- `RaceBoxSource(context, address, name)`: a recorder `DataSource`. Attach it to a
  `Recording`; it connects, reconnects on its own until stopped, and submits one
  sample set per RaceBox Data Message.
- `RaceBoxLink`: the connection manager behind the source, a `GattLink` with
  the RaceBox's `GattProfile`. Connect, MTU 517, subscribe, reconnect after
  0.5 s, 1 s, 2 s, then every 3 s; a link that is silent for 3 s, or sends
  nothing within 10 s of connecting, is restarted. State is a
  `StateFlow<LinkState>`. `GattLink` subscribes to the profile's
  characteristics one after the other (optional ones only if present and
  notifying) and can `read` one on request; the RaceChrono DIY source uses it
  too.
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
config `27`, unlock `30`) are ignored by the recorder, which sends no commands.

**Diagnostics (FBBD-89).** `RaceBoxDiagnostics` collects what the diagnostics
screen shows. Its link (a `RaceBoxLink` with a `LinkProbe`; the recorder never
has one) also reads the Device Information Service (model, serial, firmware,
hardware, manufacturer; p. 1), the MTU and RSSI, and sends two read-only
queries with an empty payload: GNSS config `27` (firmware 3.3+, p. 9) and
recording status `22` (Mini S and Micro, p. 11). An empty payload only reads; no
command that changes the device is ever sent. Missed messages are counted from
iTOW steps longer than 1.5 × the median step. The protocol has no per-satellite
signal levels, constellations, temperature, coin-cell state or firmware-update
information, so the screen shows none of these. Defaults per
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

## NMEA receivers over Bluetooth Classic (FBBD-108)

Package `nmea`: generic NMEA 0183 GNSS receivers (Qstarz BT-Q818XT / BL-818GT,
Garmin GLO / GLO 2, XGPS, ...) that stream NMEA over the Serial Port Profile
(RFCOMM, UUID `00001101-0000-1000-8000-00805F9B34FB`).

- `bondedSppDevices(context)`: devices paired in Android's Bluetooth settings
  that may be receivers. RaceBoxes, GoPros, LE-only devices and computers,
  phones and audio devices (by device class) are left out; everything else is
  offered.
- `NmeaSource(context, address, name)`: a recorder `DataSource` named `nmea`.
- `SppLink`: secure RFCOMM socket, insecure as fallback, discovery cancelled
  first; reads on its own thread; reconnects after 1 s, 2 s, 4 s, then every
  5 s; a link silent for 5 s is restarted. State is the same `LinkState` as the
  RaceBox's, with `Streaming.hz` counting sentences per second. The reading,
  back-off and watchdog live in `StreamLink`, shared with `TcpLink`.
- `NmeaLineReader`, `NmeaParser`, `NmeaEpochAssembler`, `NmeaDecoder`: pure
  Kotlin, unit tested.

**Parsing.** Only sentences with a valid `*hh` checksum are used. RMC, GGA, VTG
and GSA from any talker (GP, GN, GL, GA, GB, BD, ...). Sentences are grouped
into one epoch per fix by the UTC time of RMC and GGA; an epoch is passed on as
soon as it has both, or when a sentence with a new time arrives. VTG and GSA
have no time and join the open epoch; one that arrives after its epoch was
passed on is dropped.

**Channels.** One sample set per epoch, stamped with the recorder clock when
the epoch's first sentence arrived. Position, speed (RMC knots × 1.852, else
VTG km/h), heading and MSL altitude only with a valid fix (RMC status A and not
mode N, GGA quality 1–6). `gnss_utc_ms` from the RMC date and time; without a
date in the epoch, the last date seen, advanced at midnight. NMEA-only channels
use ids 200 to 299 (`NmeaChannels`: fix quality, fix type, HDOP, PDOP). Fix
changes and link drops are session events (`NmeaEvents`; the fix events share
their kinds with the RaceBox).

## Standard BLE services

Devices that implement a Bluetooth SIG service instead of a vendor protocol.
They share two Android pieces in the root package:

- `GattNotifyLink(context, address, GattNotifySpec(service, characteristic, ...))`:
  the `RaceBoxLink` life cycle for any notified characteristic, without the
  diagnostics probe. Connect, MTU 517, subscribe (CCCD write), reconnect after
  1 s, 2 s, 4 s, then every 5 s; a link silent for 5 s, or sending nothing
  within 15 s of connecting, is restarted. Same `LinkState`, with
  `Streaming.hz` counting notifications per second. No bonding is asked for; a
  device that needs it makes Android pair on the descriptor write.
- `scanBleDevices(context, matches)`: a Flow of `FoundBleDevice`s in range whose
  advertised name and service UUIDs pass a pure filter, strongest first.

`sigUuid(0x1819)` builds the 128-bit UUID of an assigned number; `LeReader`
reads the GATT little-endian formats (uint8/16/24, sint24, sint32) and throws
on a short value, so decoders drop truncated values in one place.

**Sources.** Bluetooth SIG GATT Specification Supplement
(<https://www.bluetooth.com/specifications/gss/>), sections Location and Speed,
Heart Rate Measurement and Date Time; the Bluetooth SIG GATT XML they replaced
(<https://github.com/oesmith/gatt-xml>) has the same layouts.

### Location and Navigation Service (FBBD-114)

Package `lns`: BLE GNSS receivers that implement the Location and Navigation
Service `0x1819`.

- `isLnsAdvertisement(name, serviceUuids)`: advertises `0x1819` and is not a
  RaceBox. `scanLnsDevices(context)` scans with it.
- `LnsSource(context, address, name)`: a recorder `DataSource` named `lns`;
  subscribes to Location and Speed `0x2A67`.
- `LocationAndSpeed`, `LnsDecoder`: pure Kotlin, unit tested.

**Location and Speed** (little-endian, fields in this order, each only when its
flag bit is set):

| Field | Format | Scale / unit | Flag bit |
| --- | --- | --- | --- |
| Flags | u16 | | |
| Instantaneous Speed | u16 | 1/100 m/s | 0 |
| Total Distance | u24 | 1/10 m | 1 |
| Latitude, Longitude | s32, s32 | 1e-7 deg | 2 |
| Elevation | s24 | 1/100 m | 3 |
| Heading | u16 | 1/100 deg | 4 |
| Rolling Time | u8 | s | 5 |
| UTC Time | date_time: year u16, month, day, hours, minutes, seconds | | 6 |

Flag bits 7–8: Position Status (0 none, 1 OK, 2 estimated, 3 last known); bit 9:
speed and distance 2D/3D; bits 10–11: elevation source (positioning system,
barometric, database, other); bit 12: heading source (0 movement, 1 magnetic
compass). A date_time with a 0 year, month or day is "not known".

**Channels.** One sample set per notification, stamped with the recorder clock
on arrival, into the same `GnssChannels` the NMEA decoder fills: `gnss_utc_ms`
whenever the UTC time is known; position, speed (m/s × 3.6), course and
elevation only with a fix. A fix is Position Status OK or Estimated, or No
Position with coordinates other than exactly 0°, 0° (servers without the
Position Status feature leave those bits at 0); Last Known Position is not a
fix. Only notifications with a location change the fix state, so a value split
over several notifications (when it does not fit the MTU) keeps the fix of its
first part. A compass heading goes in `lns_compass_heading`, not the course.
LNS-only channels use ids 300 to 399 (`LnsChannels`: position status, total
distance, compass heading). Fix changes and link drops are session events
(`LnsEvents`; the fix events share their kinds with the RaceBox and NMEA).

### Heart Rate Service (FBBD-119)

Package `hr`: chest straps, arm bands and watches in broadcast mode with the
Heart Rate Service `0x180D`.

- `isHeartRateAdvertisement(serviceUuids)`; `scanHeartRateMonitors(context)`.
- `HeartRateSource(context, address, name)`: a recorder `DataSource` named
  `heart_rate`; subscribes to Heart Rate Measurement `0x2A37`.
- `HeartRateMeasurement`, `HeartRateDecoder`: pure Kotlin, unit tested.

**Heart Rate Measurement**: flags u8; the value u8, or u16 when bit 0 is set
(bpm); Energy Expended u16 kJ when bit 3; then RR-Intervals u16 in 1/1024 s,
oldest first, as many as fit, when bit 4. Bits 1–2 are the sensor contact
status (0–1 not supported, 2 no contact, 3 contact). A trailing odd byte where
an RR-Interval would be is ignored.

**Channels** (ids 400 to 499, `HeartRateChannels`): `heart_rate` (bpm) and
`hr_energy` (kJ) in one set per notification on arrival; `heart_rate` is left
out while the monitor reports no skin contact. `hr_rr_interval` (ms) gets one
set per interval, stamped at its beat (arrival minus the newer intervals),
never before the previous interval's stamp, the session start, or after
arrival. Contact changes and link drops are session events (`HeartRateEvents`).

**Not verified on hardware.** Both decoders are tested against values built
from the specification only; capture notifications from a real LNS receiver and
a heart rate strap and add them to the tests.

**Sentence length.** Lines up to 384 characters are kept (was 160), so a
`$RC3` line with all 25 fields at their widest fits.

## NMEA and RaceChrono DIY over Wi-Fi TCP (FBBD-115)

- `TcpNmeaSource(host, port, name)`: a recorder `DataSource` named `nmea_tcp`
  for a Wi-Fi GPS receiver or a RaceChrono DIY device on the local network.
  Same decoder, channels and events as `NmeaSource`; the device event text is
  `address=<host>:<port> name=<name>`.
- `TcpLink`: a plain `java.net` socket (connect timeout 5 s, the device is the
  server, this end only reads) with the same reconnect timing, silence
  watchdog and `LinkState` as `SppLink` (`StreamLink`). Pure JVM, so it is
  tested against a loopback server. There is no standard port; the user enters
  it. A device running its own access point has no internet, so Android may
  route the socket over mobile data; the app can pass the Wi-Fi network's
  `SocketFactory` to keep it on Wi-Fi.

RaceChrono connects to the same devices as "RaceChrono DIY" over "TCP/IP
(Wi-Fi)" or Bluetooth RFCOMM ([Tutorial: DIY devices](https://racechrono.com/article/2572)).

## RaceChrono DIY $RC2 / $RC3 lines (FBBD-115)

**Source.** RaceChrono, [Tutorial: DIY devices](https://racechrono.com/article/2572):

```
$RC2,[time],[count],[xacc],[yacc],[zacc],[rpm/d1],[d2],[a1],...,[a8]*checksum
$RC3,[time],[count],[xacc],[yacc],[zacc],[gyrox],[gyroy],[gyroz],[rpm/d1],[d2],[a1],...,[a15]*checksum
```

NMEA checksum, CR LF. Acceleration in g (1.000 = +1 G), gyro in deg/s,
digital and analog values from -2000000.000 to 2000000.000. `time` is an
optional NMEA-format UTC time (empty without GPS); `count` is a line counter
0 to 65535 that wraps (empty when mixed with NMEA). The lines have **no
position fields**: a DIY device with GPS mixes ordinary NMEA 0183 sentences
into the stream, and those make the fixes as usual. Rates of about 1, 5, 10,
20, 30, 40, 50 or 100 Hz are recommended.

**Decoding.** `NmeaLineReader` checks them like any sentence, `NmeaParser`
routes `RC2`/`RC3` bodies to `parseRcLine` (outside the fix epochs), and
`NmeaDecoder` submits one sample set per line, stamped on arrival, with
acceleration and rotation in `RcChannels` (ids 500 to 505: `rc_accel_x/y/z`
in g, `rc_gyro_x/y/z` in deg/s, the units and decimals of the RaceBox's).
They are defined on the first `$RC` line only. Gaps in `count` are counted in
`NmeaDecoder.rcMissed`. The digital and analog fields are parsed (`RcLine`)
but not recorded: their meaning is set per device in RaceChrono. This works
on both `NmeaSource` (SPP) and `TcpNmeaSource`.

## RaceChrono DIY BLE GPS (FBBD-118)

Package `diy`. **Source.** "RaceChrono BLE DIY APIs", the README of
[aollin/racechrono-ble-diy-device](https://github.com/aollin/racechrono-ble-diy-device).

- `DiyBleSource(context, address, name)`: a recorder `DataSource` named
  `racechrono_diy`, on a `GattLink` with `DiyGatt.PROFILE`.
- `DiyGatt`, `parseDiyGpsMain`, `parseDiyGpsTime`, `DiyGpsDecoder`: pure
  Kotlin, unit tested with values built from the README's equations.

**GATT.** Service `0x1FF8` (`00001ff8-0000-1000-8000-00805f9b34fb`), values
big-endian and unsigned unless noted. GPS main `0x0003` (READ, NOTIFY) is
required; GPS time `0x0004` (READ, NOTIFY) is subscribed to if it notifies and
read whenever the decoder is missing it (RaceChrono polls it).

| Bytes | GPS main field | Invalid |
| --- | --- | --- |
| 0–2 | sync (top 3 bits), time since the hour = min × 30000 + s × 500 + ms / 2 (21 bits) | |
| 3 | fix quality (top 2 bits), satellites (6 bits) | sats `0x3F` |
| 4–7 | latitude, deg × 10⁷, signed | `0x7FFFFFFF` |
| 8–11 | longitude, deg × 10⁷, signed | `0x7FFFFFFF` |
| 12–13 | altitude: ((m + 500) × 10) & 0x7FFF, or ((m + 500) & 0x7FFF) \| 0x8000 | `0xFFFF` |
| 14–15 | speed: (km/h × 100) & 0x7FFF, or ((km/h × 10) & 0x7FFF) \| 0x8000 | `0xFFFF` |
| 16–17 | bearing, deg × 100 | `0xFFFF` |
| 18, 19 | HDOP, VDOP, × 10 | `0xFF` |

GPS time: sync (top 3 bits) and (year − 2000) × 8928 + (month − 1) × 744 +
(day − 1) × 24 + hour (21 bits). Main and time belong together when their sync
bits match; the README says to wait for the other one to update when they
differ. The decoder holds a main value up to 1 s for its time (asking for a
read at most once a second), keeps the last two time values so values still in
flight around the hour find theirs, and otherwise records the value without
`gnss_utc_ms`.

**Channels.** One sample set per GPS main value, stamped on arrival:
`GnssChannels` (UTC, satellites, and with a fix — quality > 0 and a valid
position — latitude, longitude, speed, heading, altitude) plus `DiyChannels`
(ids 510 to 512: fix quality, HDOP, VDOP). Ids 500 to 599 are kept for
RaceChrono DIY. Events `diy_device`, `diy_connected`, `diy_disconnected` and
the shared `gnss_fix` / `gnss_fix_lost`. The README does not define the fix
quality values; 0 is taken as no fix.

**Not used yet.** CAN-Bus main `0x0001` (a little-endian 32-bit packet id and 1
to 16 payload bytes) and its filter `0x0002` (deny all, allow all, allow one
PID, each with a notify interval) come with CAN logging in Phase 5. The
Monitor API (`0x0005`, `0x0006`), with which a device shows values the app
computes, is not offered.

## Shared link additions (FBBD-116, FBBD-117)

A `GattProfile` may have a `GattHandshake`: once per connection, after every
notification is on, `GattLink` reads its characteristic, passes the value to a
pure function, and writes the answer back with a response. Data reads
(`GattLink.read`) and the diagnostics probe wait for it. A handshake that never
leads to data is restarted by the 10 s setup timeout. `StreamLink` (and so
`SppLink`) takes an optional `messageCount` so a binary stream reports messages
per second instead of line ends.

## VBOX Sport over Bluetooth Classic (FBBD-116)

Package `vbox`: RACELOGIC VBOX Sport, RACELOGIC Serial Stream.

- `bondedVboxSports(context)`: paired devices whose name starts with `VBSport`
  (RACELOGIC's Android pairing guide shows "VBSport 00000300"). Pure filter:
  `isVboxSport(name, leOnly)`.
- `VboxSportSource(context, address, deviceName)`: a recorder `DataSource`
  named `vbox_sport`, over `SppLink`.
- `VboxSportFrameReader`, `VboxSportFix`, `VboxSportDecoder`: pure Kotlin,
  unit tested.

**Sources.** "Serial Data Format", RACELOGIC, 7/11/2014
([PDF](https://vboxmotorsport.co.uk/downloads/VBOX-Sport_Serial_Format.pdf)),
from the [VBOX Sport development portal](https://vboxmotorsport.co.uk/en/vbox-sport-development-portal)
(Serial Stream at 10 Hz; NMEA optional after a firmware update), and the
support centre's [VBOX Sport Serial (RS232) Protocol](https://en.racelogic.support/VBOX_Motorsport/Product_Info/Performance_Meters/VBOX_Sport/Knowledge_Base/VBOX_Sport_Serial_(RS232)_Protocol).
The portal's parsing sample code (`VBSSerialParseSample.zip`) could not be
downloaded from here.

**Transport.** RACELOGIC documents only an iOS MFi External Accessory stream.
Its Android guides (RaceChrono, Harry's) pair the unit in Android's Bluetooth
settings, i.e. Bluetooth Classic; that the stream is on SPP is inferred. There
is no baud rate on a virtual serial port, and the documents give none.

**Message.** `$VBSPT$`, standard channel mask (4 bytes), extended mask
(4 bytes), `,`, the channels in bit order, CRC (2 bytes). The PDF writes the
header as `$VBSPT$nnnniiii,`, the support page as `$VBSPT$,nnnniiii,`; both are
accepted and the CRC picks. Bluetooth default: standard `0x000003FF`, extended
`0x00000071`, 37 data bytes, 55 bytes in all.

| Channel (bit) | Bytes | Meaning | Used |
| --- | --- | --- | --- |
| satellites (std 0x01) | 1 | bits 0–6 count, bit 7 DGPS | `gnss_sats`, `vbox_dgps` |
| time (0x02) | 3 | 10 ms ticks since midnight UTC | `gnss_utc_ms` |
| latitude (0x04) | 4 | minutes × 100,000, + north | `gnss_lat` |
| longitude (0x08) | 4 | minutes × 100,000, **+ west** | `gnss_lon` (negated) |
| speed (0x10) | 2 | knots × 100 | `gnss_speed` |
| heading (0x20) | 2 | degrees × 100 | `gnss_heading` |
| height (0x40) | 3 | WGS84 m × 100, signed 24-bit | `gnss_alt` |
| vertical velocity (0x80) | 2 | "m/s", no scale given | no |
| long./lat. acceleration (0x100, 0x200) | 2 each | g × 100, signedness not given | no |
| HDOP (ext 0x40) | 2 | × 100 | `vbox_hdop` |

All other channel sizes are in the document and are skipped. Extended bits
above 0x40 are undocumented; such a message cannot be split and is dropped.

**Not confirmed by any VBOX Sport document, so unverified on hardware:**
- Byte order: taken as big-endian from RACELOGIC's
  [VBOX 4 serial protocol](https://en.racelogic.support/VBOX_Automotive/Product_Info/VBOX_Data_Loggers/VBOX_4_Product_Range/VBOX_4_Knowledge_Base/VBOX_4_Serial_(RS232)_Protocol)
  (same family and layout: "The byte ordering format is big-endian (Motorola)").
- CRC range: from `$` to the last data byte, as in RACELOGIC's
  [VBOX II RS232 format](https://www.racelogic.co.uk/_downloads/vbox/Application_Notes/VBOXII_rs232_Format.pdf)
  copy of the same checksum code. The CRC itself (polynomial 0x1021, initial 0)
  is in the VBOX Sport document; CRC bytes are read big-endian too.
- If either guess is wrong, every message fails its CRC, nothing is recorded,
  and the session gets one `vbox_sport_no_valid_messages` event per connection.

**Channels.** One sample set per message. The stream has no date and no fix
flag: UTC takes its date from the phone clock (the day that puts the time
nearest to it), and position, speed, heading, altitude and UTC are only
included with at least 3 satellites and a position other than 0°, 0° (a design
choice). Altitude is ellipsoidal (WGS84), not MSL as for NMEA and the RaceBox.
VBOX Sport channels use ids 600 to 619 (`VboxSportChannels`).

## Dragy Pro / DRG70 over BLE (FBBD-117)

Package `dragy`. **Unverified on hardware.** Dragy publishes no protocol; this
follows the community reverse engineering in
[jremick/dragy-dash](https://github.com/jremick/dragy-dash) (Apache-2.0,
`docs/DRAGY_CAPTURE.md` v0.2.0 and its Swift sources at commit `fdb8241`),
captured on a Dragy Pro only. The Dragy Pro's IMU is not read.

- `scanDragys(context)`: Dragys in range, strongest first. Pure filter
  `isDragy(name, serviceUuids, manufacturerData)`: name contains "dragy",
  "drg70" or "drg69" or starts with "DRG", or service `FD00` is advertised, or
  the manufacturer data contains "dragy" (dragy-dash's rule).
- `DragySource(context, address, deviceName)`: a recorder `DataSource` named
  `dragy`, on a `GattLink` with `DragyGatt.PROFILE`.
- `NavPvt`, `DragyDecoder`, `handshakeResponse`: pure Kotlin, unit tested.

**GATT.** Service `FD00` (16-bit SIG base UUIDs). `FD02` notifies; `FD03`
(read, write) is the handshake; `FD04` (device control) is not used. Live data
starts only after reading two bytes a, b from `FD03` and writing
`[a, b, a XOR b, a AND b]` back.

**Data.** `FD02` carries UBX frames, fragmented, reassembled with the RaceBox's
`UbxFrameReader` (same `B5 62` framing and Fletcher checksum). Only
UBX-NAV-PVT (class `01`, id `07`, 92-byte payload, 100-byte frame) is used,
25 Hz per RaceChrono. The payload layout is u-blox's (M8 protocol
specification UBX-13003221, section 32.18.14); the PDF table could not be read
here, so each offset was checked against u-blox's own `ubxlib`
(`gnss/src/u_gnss_pos.c`) and `ublox_msgs/NavPVT.msg`:

| Offset | Type | Field | Use |
| --- | --- | --- | --- |
| 0 | U4 | iTOW, ms | |
| 4–10 | U2, U1 ×5 | year, month, day, hour, min, sec | `gnss_utc_ms` if valid bits 0 and 1 |
| 11 | X1 | valid | |
| 16 | I4 | nano, ns (signed) | `gnss_utc_ms` |
| 20 | U1 | fixType | `dragy_fix_type` |
| 21 | X1 | flags, bit 0 gnssFixOK | fix |
| 23 | U1 | numSV | `gnss_sats` |
| 24, 28 | I4 | lon, lat, 1e-7 deg | `gnss_lon`, `gnss_lat` |
| 32, 36 | I4 | height, hMSL, mm | `gnss_alt` (MSL) |
| 40, 44 | U4 | hAcc, vAcc, mm | `dragy_hacc` |
| 60 | I4 | gSpeed, mm/s | `gnss_speed` |
| 64 | I4 | headMot, 1e-5 deg | `gnss_heading` |
| 68, 72 | U4 | sAcc mm/s, headAcc 1e-5 deg | `dragy_speed_acc` |
| 76 | U2 | pDOP × 0.01 | `dragy_pdop` |

A fix is gnssFixOK with fixType 3 (3D) or 4 (GNSS + dead reckoning); position,
speed, heading and altitude are only included with one. Dragy channels use ids
620 to 639 (`DragyChannels`).

**Tests.** VBOX and NAV-PVT fixtures are built from the documents' field lists
(`VboxSportTestData.kt`, `NavPvtTestData.kt`), not captured; the CRC is checked
against the standard CRC-16/XMODEM check value. Capture real streams when a
VBOX Sport and a Dragy are available.
