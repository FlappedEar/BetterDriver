package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.log.IosLog
import platform.Foundation.NSLock

/**
 * The app's log lines while a test runs (FBBD-422). The coach and the cue clips log from their own
 * queues while the test reads the lines, so they are kept under a lock and read as a copy: a list
 * appended to by the sink while the test filtered it threw ConcurrentModificationException.
 */
class LogLines {
    private val lock = NSLock()
    private val lines = mutableListOf<String>()

    /** Sends the app's log here; the test's cleanup sets IosLog.sink back to null. */
    fun capture(): LogLines {
        IosLog.sink = { tag, message ->
            lock.lock()
            try {
                lines += "$tag: $message"
            } finally {
                lock.unlock()
            }
        }
        return this
    }

    /** The lines logged so far, as a copy. */
    fun all(): List<String> {
        lock.lock()
        try {
            return lines.toList()
        } finally {
            lock.unlock()
        }
    }
}
