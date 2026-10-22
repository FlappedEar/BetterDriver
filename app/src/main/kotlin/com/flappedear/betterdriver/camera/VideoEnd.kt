package com.flappedear.betterdriver.camera

import com.flappedear.betterdriver.recording.CameraFinishWaitMs

/**
 * The end of a session's phone video (audit A13): after a stop, [CameraService] keeps its foreground
 * and its link to the recorder until the camera says nothing is left to finish, or until [timeoutMs]
 * pass, whichever comes first. Main thread; [schedule] and [cancel] are the main handler's.
 */
internal class VideoEnd(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val timeoutMs: Long = CameraFinishWaitMs,
) {
    private var timeout: Runnable? = null

    /** A stop is waiting for its camera. */
    val waiting: Boolean get() = timeout != null

    /**
     * Calls [stop] with the callback its camera calls once finished, then [then] exactly once: with
     * false when the camera finished, true at the timeout. A finish after the timeout is ignored.
     */
    fun begin(stop: (finished: () -> Unit) -> Unit, then: (timedOut: Boolean) -> Unit) {
        val run = object : Runnable {
            override fun run() = end(this, timedOut = true, then)
        }
        timeout = run
        schedule(run, timeoutMs)
        stop { end(run, timedOut = false, then) }
    }

    /** The service ends for another reason: nothing is waited for any more. */
    fun abandon() {
        timeout?.let(cancel)
        timeout = null
    }

    private fun end(run: Runnable, timedOut: Boolean, then: (Boolean) -> Unit) {
        if (timeout !== run) return
        timeout = null
        cancel(run)
        then(timedOut)
    }
}
