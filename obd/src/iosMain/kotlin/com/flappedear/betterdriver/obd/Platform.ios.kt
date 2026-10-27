package com.flappedear.betterdriver.obd

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

private const val MsPerSecond = 1_000.0

actual class ObdMonitor actual constructor() {
    private val condition = NSCondition()

    actual fun lock() = condition.lock()

    actual fun unlock() = condition.unlock()

    // iOS threads are not interrupted: stopping closes the connection, which signals the waiter.
    actual fun await(ms: Long) {
        condition.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(ms / MsPerSecond))
    }

    actual fun signalAll() = condition.broadcast()
}

actual fun isNoPermission(e: Throwable): Boolean = false

actual fun isInterrupted(e: Throwable): Boolean = false
