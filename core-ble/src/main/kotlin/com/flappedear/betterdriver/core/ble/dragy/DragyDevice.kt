package com.flappedear.betterdriver.core.ble.dragy

import com.flappedear.betterdriver.core.ble.GattHandshake
import com.flappedear.betterdriver.core.ble.GattProfile
import java.util.UUID

/**
 * BLE identifiers of the Dragy Pro / DRG70 (FBBD-117). Dragy publishes no protocol; everything
 * here is from the community reverse engineering in https://github.com/jremick/dragy-dash
 * (Apache-2.0; `docs/DRAGY_CAPTURE.md` v0.2.0 of 2026-06-03 and `DragyBluetoothService.swift` at
 * commit fdb8241), captured on a Dragy Pro only. **Unverified on hardware by us**; other Dragy
 * models and firmware may differ.
 */
object DragyGatt {
    /** Primary service `FD00`, also advertised. */
    val SERVICE: UUID = UUID.fromString("0000fd00-0000-1000-8000-00805f9b34fb")

    /** `FD02` "spp data rx": notifies UBX NAV-PVT packets, fragmented, once the handshake is done. */
    val DATA: UUID = UUID.fromString("0000fd02-0000-1000-8000-00805f9b34fb")

    /** `FD03` "spp config" (read, write): the challenge and response. */
    val CONFIG: UUID = UUID.fromString("0000fd03-0000-1000-8000-00805f9b34fb")

    /** `FD04` "device control" (read, write, notify). Not used: its battery byte is a guess upstream. */
    val CONTROL: UUID = UUID.fromString("0000fd04-0000-1000-8000-00805f9b34fb")

    /**
     * What the link subscribes to: [DATA]. Subscribing alone gives no live data; it starts after the
     * [CONFIG] handshake ([handshakeResponse]), done once per connection.
     */
    val PROFILE = GattProfile(
        threadName = "dragy-link",
        service = SERVICE,
        notify = listOf(DATA),
        handshake = GattHandshake(CONFIG, ::handshakeResponse),
    )

    /** Observed advertised name: `DRG70-` and a 6-character suffix. */
    const val NAME_PREFIX = "DRG"
}

/**
 * The answer to the Dragy's challenge: read `FD03`, take its first two bytes a and b, and write
 * `[a, b, a XOR b, a AND b]` back (dragy-dash `respondToShakeChallengeIfNeeded`). Null if the
 * challenge is shorter than two bytes.
 */
fun handshakeResponse(challenge: ByteArray): ByteArray? {
    if (challenge.size < 2) return null
    val a = challenge[0].toInt()
    val b = challenge[1].toInt()
    return byteArrayOf(a.toByte(), b.toByte(), (a xor b).toByte(), (a and b).toByte())
}

/** A Dragy seen in a scan. */
data class FoundDragy(val name: String, val address: String, val rssi: Int)

/**
 * Whether an advertisement is a Dragy, as dragy-dash decides (`isDragyCandidate`): the name
 * contains "dragy", "drg70" or "drg69" or starts with "DRG" (any case), or [serviceUuids] has
 * `FD00`, or [manufacturerData] contains "dragy" as ASCII.
 */
fun isDragy(name: String?, serviceUuids: Collection<UUID> = emptyList(), manufacturerData: ByteArray? = null): Boolean {
    val n = name.orEmpty().lowercase()
    return listOf("dragy", "drg70", "drg69").any(n::contains) || n.startsWith(DragyGatt.NAME_PREFIX.lowercase()) ||
        DragyGatt.SERVICE in serviceUuids ||
        manufacturerData?.toString(Charsets.ISO_8859_1)?.lowercase()?.contains("dragy") == true
}
