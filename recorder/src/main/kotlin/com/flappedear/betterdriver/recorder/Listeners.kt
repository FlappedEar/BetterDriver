package com.flappedear.betterdriver.recorder

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val MaxErrorText = 500
private const val ErrorLogIntervalNanos = 1_000_000_000L

/**
 * Delivers recorder output to listeners on one background thread. If listeners fall behind, the
 * oldest undelivered updates are dropped; the recording itself is never affected.
 */
class ListenerHub internal constructor(
    name: String,
    capacity: Int,
    private val onError: (String) -> Unit,
) {
    private val listeners = CopyOnWriteArrayList<RecorderListener>()
    private var lastErrorNanos: Long? = null
    private var suppressedErrors = 0
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity),
        { r -> Thread(r, "recorder-listeners-$name").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    fun add(listener: RecorderListener) {
        listeners += listener
    }

    fun remove(listener: RecorderListener) {
        listeners -= listener
    }

    @Suppress("TooGenericExceptionCaught") // Isolation: a crashing consumer must not stop the recording.
    internal fun dispatch(block: (RecorderListener) -> Unit) {
        if (listeners.isEmpty()) return
        try {
            executor.execute {
                for (l in listeners) {
                    try {
                        block(l)
                    } catch (t: Throwable) {
                        logError(t)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Shut down: the recording has ended.
        }
    }

    internal fun shutdown() = executor.shutdown()

    /** Logs at most one listener error per second, with a count of the ones left out, so a listener
     * failing on every update cannot flood the session. Runs on the single listener thread. */
    private fun logError(t: Throwable) {
        val now = System.nanoTime()
        val last = lastErrorNanos
        if (last != null && now - last < ErrorLogIntervalNanos) {
            suppressedErrors++
            return
        }
        lastErrorNanos = now
        val suppressed = if (suppressedErrors > 0) " (+$suppressedErrors earlier errors not logged)" else ""
        suppressedErrors = 0
        onError(t.toString().take(MaxErrorText) + suppressed)
    }
}
