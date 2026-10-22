package com.flappedear.betterdriver.core.ble

/**
 * Lets notifications through only from the link's current connection (audit A09). Every other GATT
 * callback is posted to the link's thread and checked against the connection there; notifications
 * are handled on the binder thread as they come, so a late one from a connection already closed
 * would otherwise reach the decoder after its reset for the next connection and refresh the
 * watchdog. [open] and [close] run on the link's thread; [deliver] on the binder thread.
 *
 * [deliver] holds a lock while it hands the bytes on, and [close] takes the same lock: once
 * [close] returns, no delivery from the old connection is still running, so a decoder reset made
 * after it cannot be followed by old bytes. The lock is uncontended except at a close, so the
 * notification path stays fast.
 */
class CurrentConnection<C : Any> {
    private val lock = Any()
    private var current: C? = null

    /** [connection] is now the link's connection; notifications from any other are dropped. */
    fun open(connection: C) = synchronized(lock) { current = connection }

    /** No connection: every notification is dropped until the next [open]. */
    fun close() = synchronized(lock) { current = null }

    /** Runs [handle] when [from] is the current connection; false when the notification was dropped. */
    fun deliver(from: C, handle: () -> Unit): Boolean = synchronized(lock) {
        if (from !== current) return false
        handle()
        true
    }
}
