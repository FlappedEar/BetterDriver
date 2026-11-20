package com.flappedear.betterdriver.recorder.video

import kotlin.math.min
import kotlin.math.pow

/**
 * When phone video starts its next file after failed ones, for both apps (FBBD-441 iOS, FBBD-442
 * Android): the phone camera never gives up for the session. The first failure starts the next file at
 * once; further failures in a row wait 2, 4, 8 ... at most [LONGEST_WAIT] seconds; a file that recorded
 * for [STEADY_SECONDS] ends the run. Times are seconds on any one clock that does not jump.
 */
class PhoneFileRetry {
    /** Failed files since the last steady one. */
    var inARow = 0
        private set

    /** All failed files in the session, for the log. */
    var total = 0
        private set

    private var notBefore = Double.NEGATIVE_INFINITY

    /** A file that started at [startedAt] failed at [now]. */
    fun failed(startedAt: Double, now: Double) {
        if (now - startedAt >= STEADY_SECONDS) inARow = 0
        inARow += 1
        total += 1
        notBefore = now + wait
    }

    /** How long the next file waits after the latest failure, in seconds. */
    val wait: Double
        get() = if (inARow <= 1) 0.0 else min(2.0.pow(inARow - 1), LONGEST_WAIT)

    /** Whether a new file may start at [now]. */
    fun mayOpen(at: Double): Boolean = at >= notBefore

    companion object {
        const val STEADY_SECONDS = 60.0
        const val LONGEST_WAIT = 30.0
    }
}

/**
 * The track screen's alert while the phone records no video because of a fault (FBBD-468 iOS,
 * FBBD-470 Android): an amber camera icon alone is easy to miss while driving. The data recording
 * carries on either way.
 */
object PhoneVideoAlert {
    const val TITLE = "Phone video stopped"

    /** The alert's detail while the camera tries again after a fault. */
    const val RETRYING = "Retrying, data still recording"

    /** The alert's detail when the camera cannot record in this session. */
    const val OFF = "Off this session, data still recording"

    /** Android's camera status texts (status strip) that mean a fault. */
    const val STATUS_RESTARTING = "Restarting"
    const val STATUS_ERROR = "Camera error"
    const val STATUS_NOT_SUPPORTED = "Not supported"

    /** The alert's detail for an Android camera status text, or null when it is no fault. */
    fun detail(status: String?): String? = when (status) {
        STATUS_RESTARTING -> RETRYING
        STATUS_ERROR, STATUS_NOT_SUPPORTED -> OFF
        else -> null
    }
}

/**
 * How long one phone video file records before the next one starts, on both apps (FBBD-468, audit
 * 7 Nov BD-T02). An MP4 gets its index only when it is finished, so a killed app, a crash or a dead
 * phone leaves the file it was writing unplayable. A new file every [SECONDS] bounds that loss to one
 * file's length instead of the whole session. Review, the data video, the composite laps and the VBO
 * export join a session's files on the session clock, so a file change costs at most a frame or two.
 */
object PhoneFileLength {
    const val SECONDS = 300.0

    /** [SECONDS] in microseconds, for encoders that count in them. */
    const val MICROS = 300_000_000L

    /** [SECONDS] in milliseconds, for CameraX's duration limit. */
    const val MILLIS = 300_000L

    /** Whether a file that started at [startedAt] is full at [now] (seconds); [limit] for the tests. */
    fun full(startedAt: Double, now: Double, limit: Double = SECONDS): Boolean = now - startedAt >= limit
}
