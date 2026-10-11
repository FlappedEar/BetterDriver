package com.flappedear.betterdriver.core.ble

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** The RaceBox's UART TX notifications: UBX packets. */
private val RaceBoxProfile = GattProfile("racebox-link", RaceBoxGatt.UART_SERVICE, listOf(RaceBoxGatt.UART_TX))

/**
 * Keeps a BLE link to one RaceBox (FBBD-43): a [GattLink] subscribed to the UART TX notifications.
 * It connects, asks for a larger MTU, and reconnects on its own after a drop, a GATT error, or 3 s
 * without data. [onData] gets each notification's bytes on Android's binder thread and must return
 * quickly. With a [probe] (the diagnostics screen) the link also reads device information and sends
 * read-only queries.
 */
class RaceBoxLink(
    context: Context,
    address: String,
    onData: (ByteArray) -> Unit,
    onState: (LinkState) -> Unit = {},
    probe: LinkProbe? = null,
) {
    private val link = GattLink(context, address, RaceBoxProfile, { _, bytes -> onData(bytes) }, onState, probe)

    val state: StateFlow<LinkState> get() = link.state

    fun start() = link.start()

    /** Disconnects and ends the link's thread. The link cannot be started again. */
    fun stop() = link.stop()
}
