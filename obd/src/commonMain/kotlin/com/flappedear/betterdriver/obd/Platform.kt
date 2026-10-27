package com.flappedear.betterdriver.obd

import kotlin.time.TimeSource

/**
 * A failed or closed connection to an adapter. On the JVM it is java.io.IOException, so the Android
 * transports (sockets, GATT) throw and catch the same type as before (FBBD-333).
 */
@Suppress("UnusedPrivateProperty") // detekt reads the expected constructors' parameters as properties.
expect open class IOException : Exception {
    constructor()
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}

/**
 * A lock with one condition, for the threads that meet in a connection: the transport's, which
 * feeds bytes, and the poller's, which waits for them. [await] waits up to [ms] for [signalAll] and
 * throws an [IOException] when the waiting thread is interrupted (the JVM's stop, FBBD-269); its
 * interrupt flag stays set.
 */
expect class ObdMonitor() {
    fun lock()

    fun unlock()

    fun await(ms: Long)

    fun signalAll()
}

/** Runs [body] holding the monitor's lock. */
inline fun <T> ObdMonitor.locked(body: () -> T): T {
    lock()
    try {
        return body()
    } finally {
        unlock()
    }
}

/** Whether [e] is the platform's refusal to use a device without permission (the JVM's SecurityException). */
expect fun isNoPermission(e: Throwable): Boolean

/** Whether [e] is a thread interrupted while it waited (the JVM's InterruptedException). */
expect fun isInterrupted(e: Throwable): Boolean

private val Start = TimeSource.Monotonic.markNow()

/** A monotonic clock in nanoseconds, for deadlines (System.nanoTime on the JVM). */
internal fun monoNanos(): Long = Start.elapsedNow().inWholeNanoseconds
