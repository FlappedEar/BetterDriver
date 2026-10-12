package com.flappedear.betterdriver.core.ble

/** Messages the lowest delay is taken over: 10 s at 25 Hz. */
private const val WindowMessages = 250

/** A longer step in time of week (or none, or a step back) is a reconnect or a clock jump. */
private const val MaxStepMs = 200L

/** A delay this much below the current lowest means the receiver's time jumped forward. */
private const val MaxOffsetDropMicros = 100_000L

private const val MicrosPerMilli = 1_000L

/**
 * Stamps RaceBox messages on the recorder's clock from the receiver's time of week (FBBD-182).
 * Stamping each message when its notification arrived put Bluetooth delivery jitter into every
 * sample: in Arek's 2026-10-12 drive the VBO's avisynctime moved 0 to 99 ms against GNSS time
 * between rows 40 ms apart (median 27 ms). The receiver's own time is exact, so a message is
 * stamped at its time of week plus the lowest arrival delay seen over the last [WindowMessages]
 * messages: the least-delayed messages show the link's real latency, and the sliding window
 * follows the drift between the phone's and the receiver's clocks.
 *
 * A stamp is never later than the arrival and never earlier than the previous stamp. After a
 * reconnect, a step back or a jump in the receiver's time, it starts over from the arrival time.
 */
internal class ReceiverClock(private val windowMessages: Int = WindowMessages) {
    /** (message number, arrival minus time of week), lowest first: a sliding-window minimum. */
    private val window = ArrayDeque<Pair<Long, Long>>()
    private var count = 0L
    private var lastTowMs: Long? = null
    private var lastStamp = Long.MIN_VALUE

    fun stamp(arrivalMicros: Long, iTowMs: Long): Long = stamp(arrivalMicros, listOf(iTowMs)).single()

    /**
     * Stamps the messages of one notification, oldest first. All of them arrived together, so the
     * newest, least delayed one sets the delay for all: stamping each before seeing the next would
     * give messages 40 ms apart nearly the same stamp. Where the receiver's time breaks off inside
     * the notification (a week wrap, a jump), the messages before it are stamped first, with the
     * delay from before the break.
     */
    fun stamp(arrivalMicros: Long, iTowsMs: List<Long>): List<Long> {
        val stamps = ArrayList<Long>(iTowsMs.size)
        var segment = 0
        iTowsMs.forEachIndexed { i, tow ->
            if (!continues(arrivalMicros, tow)) {
                stampSegment(iTowsMs.subList(segment, i), stamps)
                segment = i
                window.clear()
            }
            observe(arrivalMicros, tow)
        }
        stampSegment(iTowsMs.subList(segment, iTowsMs.size), stamps)
        return stamps
    }

    private fun stampSegment(iTowsMs: List<Long>, into: MutableList<Long>) {
        if (iTowsMs.isEmpty()) return
        val lowest = window.first().second
        iTowsMs.mapTo(into) { tow -> maxOf(tow * MicrosPerMilli + lowest, lastStamp + 1).also { lastStamp = it } }
    }

    /** Whether a message continues the receiver's time the window was taken over. */
    private fun continues(arrivalMicros: Long, iTowMs: Long): Boolean {
        val step = lastTowMs?.let { iTowMs - it }
        val lowest = window.firstOrNull()?.second
        val offset = arrivalMicros - iTowMs * MicrosPerMilli
        return step != null && step in 1..MaxStepMs && lowest != null && offset >= lowest - MaxOffsetDropMicros
    }

    private fun observe(arrivalMicros: Long, iTowMs: Long) {
        lastTowMs = iTowMs
        val offset = arrivalMicros - iTowMs * MicrosPerMilli
        count++
        while (window.isNotEmpty() && window.last().second >= offset) window.removeLast()
        window.addLast(count to offset)
        while (window.first().first <= count - windowMessages) window.removeFirst()
    }

    fun reset() {
        window.clear()
        lastTowMs = null
    }
}
