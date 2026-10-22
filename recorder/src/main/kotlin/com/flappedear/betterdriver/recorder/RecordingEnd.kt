package com.flappedear.betterdriver.recorder

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tells the host of [recording] (the recorder service) that it has ended: exactly once, with the
 * writer's own reason, and only once the writer has really stopped (Audit A03). Until then the host
 * keeps the recording as its running one, with its foreground service and wake lock, and starts no
 * other.
 *
 * A user stop that the writer does not finish within the wait (a disk write or sync stalling) is not
 * an end: [onEnded] comes when the writer does finish. A stop the recorder makes itself (storage full,
 * write error) reaches it through the recording's status.
 */
class RecordingEnd(private val recording: Recording, private val onEnded: (reason: String) -> Unit) {
    private val ended = AtomicBoolean(false)

    val isEnded: Boolean get() = ended.get()

    init {
        recording.listeners.add(object : RecorderListener {
            override fun onStatus(status: RecorderStatus) {
                if (status is RecorderStatus.Stopped) end(status.reason)
            }
        })
        // Stopped before the listener was added: the status went out already, but is set before it is.
        recording.finalStatus?.let { end(it.reason) }
    }

    /**
     * Asks the recording to stop and waits up to [timeoutMs] for the writer. True when it has stopped,
     * and [onEnded] has run. False when it is still finishing: a thread waits for it and ends it then.
     */
    fun stop(reason: String, timeoutMs: Long = StopWaitMs): Boolean {
        if (recording.stop(reason, timeoutMs)) {
            end(recording.finalStatus?.reason ?: reason)
            return true
        }
        Thread({
            // No time limit: a recording that never finishes keeps its protection rather than losing it.
            while (!recording.awaitStopped(StopWaitMs)) Unit
            end(recording.finalStatus?.reason ?: reason)
        }, "recorder-end-${recording.id}").apply { isDaemon = true }.start()
        return false
    }

    private fun end(reason: String) {
        if (ended.compareAndSet(false, true)) onEnded(reason)
    }

    companion object {
        /** As [Recording.stop]'s own default. */
        const val StopWaitMs = 10_000L
    }
}
