package com.flappedear.betterdriver.core.ble

/**
 * The Bluetooth LE links of this process that are connecting and do not stream yet (FBBD-282). On
 * 21 Oct 2026 the RaceBox's first setup ran while the Bluetooth LE OBD adapter connected: it took
 * 4.7 s to connect (0.6 to 1.7 s on other drives) and its service lookup never answered. So a link
 * that can wait (the OBD adapter, [awaitQuiet]) lets the others finish first, for a few seconds at
 * most.
 */
class BleSetups {
    private val lock = Object()
    private val connecting = mutableSetOf<String>()

    /** [address] started connecting. */
    fun started(address: String) = synchronized(lock) {
        connecting += address
    }

    /** [address] streams, or stopped. */
    fun ended(address: String) = synchronized(lock) {
        if (connecting.remove(address)) lock.notifyAll()
    }

    /**
     * Waits until no link but [self] is connecting, at most [maxMs]; the addresses still connecting
     * then, empty when none.
     */
    fun awaitQuiet(self: String, maxMs: Long): Set<String> = synchronized(lock) {
        val until = System.nanoTime() + maxMs * NanosPerMs
        var left = maxMs
        while (left > 0 && (connecting - self).isNotEmpty()) {
            lock.wait(left)
            left = (until - System.nanoTime()) / NanosPerMs
        }
        connecting - self
    }

    companion object {
        private const val NanosPerMs = 1_000_000L

        /** The process's links. */
        val Shared = BleSetups()
    }
}
