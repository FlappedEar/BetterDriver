package com.flappedear.betterdriver.core.ble

/**
 * Where a link to a device is: the RaceBox and RaceChrono DIY BLE links ([GattLink]) and the NMEA
 * links over SPP and TCP ([com.flappedear.betterdriver.core.ble.nmea.StreamLink]). Shown in the UI
 * and logged by the data sources.
 */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState

    /** Connected and receiving; [hz] is the measured message rate over the last second. */
    data class Streaming(val hz: Int) : LinkState

    /** Lost; the next attempt starts after [delayMs]. [reason] is for logs. */
    data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : LinkState
}
