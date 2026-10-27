package com.flappedear.betterdriver.obd

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

actual typealias IOException = java.io.IOException

actual class ObdMonitor actual constructor() {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    actual fun lock() = lock.lock()

    actual fun unlock() = lock.unlock()

    actual fun await(ms: Long) {
        try {
            changed.await(ms, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // An uncaught InterruptedException killed the recorder process at Stop (FBBD-269).
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
    }

    actual fun signalAll() = changed.signalAll()
}

actual fun isNoPermission(e: Throwable): Boolean = e is SecurityException

actual fun isInterrupted(e: Throwable): Boolean = e is InterruptedException
