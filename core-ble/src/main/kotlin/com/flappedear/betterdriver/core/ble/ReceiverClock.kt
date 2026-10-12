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

    fun stamp(arrivalMicros: Long, iTowMs: Long): Long {
        val step = lastTowMs?.let { iTowMs - it }
        lastTowMs = iTowMs
        val towMicros = iTowMs * MicrosPerMilli
        val offset = arrivalMicros - towMicros
        val lowest = window.firstOrNull()?.second
        val jumped = lowest != null && offset < lowest - MaxOffsetDropMicros
        val continues = step != null && step in 1..MaxStepMs && !jumped
        if (!continues) window.clear()
        count++
        while (window.isNotEmpty() && window.last().second >= offset) window.removeLast()
        window.addLast(count to offset)
        while (window.first().first <= count - windowMessages) window.removeFirst()
        val stamped = maxOf(towMicros + window.first().second, lastStamp + 1)
        lastStamp = stamped
        return stamped
    }

    fun reset() {
        window.clear()
        lastTowMs = null
    }
}
