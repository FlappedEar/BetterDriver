package com.flappedear.betterdriver.recorder

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val MaxErrorText = 500

/**
 * Live consumers (track screen, live engine, cue engine). They run on their own thread, every call
 * is wrapped, and the recording never waits for them or stops because of them (FBBD-69).
 */
interface RecorderListener {
    fun onChannel(def: ChannelDef) = Unit
    fun onSamples(set: SampleSet) = Unit
    fun onEvent(event: RecorderEvent) = Unit
    fun onStatus(status: RecorderStatus) = Unit
}

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
                        onError(t.toString().take(MaxErrorText))
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Shut down: the recording has ended.
        }
    }

    internal fun shutdown() = executor.shutdown()
}
