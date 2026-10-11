package com.flappedear.betterdriver.core.ble

import java.util.UUID

/**
 * What a [GattLink] subscribes to: the characteristics of [service] in [notify], in that order (one
 * descriptor write at a time). Those in [optional] are skipped when the device lacks them or they
 * cannot notify; a missing required one fails the connection. [threadName] names the link's thread.
 */
class GattProfile(
    val threadName: String,
    val service: UUID,
    val notify: List<UUID>,
    val optional: Set<UUID> = emptySet(),
)

/** Client Characteristic Configuration descriptor, written to turn notifications on. */
val CccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** A Bluetooth SIG 16-bit UUID as a full 128-bit one on the Bluetooth base UUID. */
fun bluetoothUuid(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
