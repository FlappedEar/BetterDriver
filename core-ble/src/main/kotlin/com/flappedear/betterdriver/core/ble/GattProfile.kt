package com.flappedear.betterdriver.core.ble

import java.util.UUID

/**
 * What a [GattLink] subscribes to: the characteristics of [service] in [notify], in that order (one
 * descriptor write at a time). Those in [optional] are skipped when the device lacks them or they
 * cannot notify; a missing required one fails the connection. [threadName] names the link's thread.
 * A device that streams only after a challenge and response (the Dragy, FBBD-117) has a [handshake].
 */
class GattProfile(
    val threadName: String,
    val service: UUID,
    val notify: List<UUID>,
    val optional: Set<UUID> = emptySet(),
    val handshake: GattHandshake? = null,
)

/**
 * A challenge and response done once per connection, after every notification is on: read
 * [characteristic] (in the profile's service), pass its value to [respond], and write the answer
 * back to the same characteristic with a response. [respond] is a pure function; null means the
 * challenge is unusable, which fails the connection attempt so it is retried. A handshake that
 * never leads to data is restarted by the link's setup timeout.
 */
class GattHandshake(val characteristic: UUID, val respond: (ByteArray) -> ByteArray?)

/** Client Characteristic Configuration descriptor, written to turn notifications on. */
val CccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** A Bluetooth SIG 16-bit UUID as a full 128-bit one on the Bluetooth base UUID. */
fun bluetoothUuid(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
