package com.flappedear.betterdriver.core.ble

/**
 * Whether a link that falls behind should ask Android again for the fast link (FBBD-218, FBBD-305).
 * The request is for a device whose connection Android relaxed; one that is already at the high
 * priority interval on the 2M PHY gains nothing from it. On 28 Oct 2026 (20:04, FR-S) the RaceBox sat
 * at 15 ms on 2M the whole drive and the app asked again 10 times for single missing messages; each of
 * the drive's three 640-760 ms gaps began 110-130 ms after such a request, during the connection
 * update it starts. 21 Oct 15:46 had the same shape (one 680 ms gap ~1 s after a request).
 */
class FastLinkGate(private val fastIntervalMs: Double = 15.0) {
    private var intervalMs: Double? = null
    private var phy2m: Boolean? = null

    /** A new connection: nothing known about it yet. */
    fun newConnection() {
        intervalMs = null
        phy2m = null
    }

    /** Android reported the connection's interval. */
    fun interval(ms: Double) {
        intervalMs = ms
    }

    /** Android reported the PHY in use (both directions). */
    fun phy(tx2m: Boolean, rx2m: Boolean) {
        phy2m = tx2m && rx2m
    }

    /** True when asking again could help: the interval or the PHY is slower than asked for, or unknown. */
    fun worthAsking(): Boolean {
        val ms = intervalMs ?: return true
        return ms > fastIntervalMs || phy2m != true
    }

    /** For the debug log when a request is skipped. */
    fun describe(): String = "interval ${intervalMs ?: "unknown"} ms, 2M PHY ${phy2m ?: "unknown"}"
}
