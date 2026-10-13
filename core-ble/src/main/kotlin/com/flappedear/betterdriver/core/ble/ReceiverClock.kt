package com.flappedear.betterdriver.core.ble

/**
 * How fast the lowest delay may rise, in microseconds per second (100 ppm): twice the drift of a
 * phone's clock against GNSS time (crystals are within about 50 ppm), and far below a Bluetooth
 * backlog building up (Arek's 2026-10-13 drive: about 275 ms in two minutes, over 2,000 ppm).
 */
private const val MaxRiseMicrosPerSecond = 100L
private const val MicrosPerSecond = 1_000_000L

/** Messages the exact lowest delay is taken over: 10 s at 25 Hz. */
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
 * stamped at its time of week plus the lowest arrival delay seen over the last [windowMessages]
 * messages: the least-delayed messages show the link's real latency, and the sliding window
 * follows the drift between the phone's and the receiver's clocks.
 *
 * The window alone also followed a backlog: when the link carries fewer messages than the receiver
 * sends, they queue in the RaceBox and every one arrives late. On 2026-10-13 that delay grew by about
 * 275 ms and stayed (FBBD-218), so every sample was stamped up to 0.3 s late against the video. So
 * the delay used is also never more than any earlier lowest plus [maxRiseMicrosPerSecond] of phone
 * time since: enough to follow the clocks' drift, far too little to follow a backlog.
 *
 * A stamp is never later than the arrival and never earlier than the previous stamp. After a
 * reconnect, a step back or a jump in the receiver's time, it starts over from the arrival time.
 */
internal class ReceiverClock(
    private val windowMessages: Int = WindowMessages,
    private val maxRiseMicrosPerSecond: Long = MaxRiseMicrosPerSecond,
) {
    /** (message number, arrival minus time of week), lowest first: a sliding-window minimum. */
    private val window = ArrayDeque<Pair<Long, Long>>()
    private var count = 0L

    /** The lowest arrival minus time of week ever seen, plus the rise allowed since; null at the start. */
    private var risen: Long? = null
    private var risenAt = 0L
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
                clear()
            }
            observe(arrivalMicros, tow)
        }
        stampSegment(iTowsMs.subList(segment, iTowsMs.size), stamps)
        return stamps
    }

    private fun stampSegment(iTowsMs: List<Long>, into: MutableList<Long>) {
        if (iTowsMs.isEmpty()) return
        val delay = lowest() ?: return
        iTowsMs.mapTo(into) { tow -> maxOf(tow * MicrosPerMilli + delay, lastStamp + 1).also { lastStamp = it } }
    }

    /** Whether a message continues the receiver's time the lowest delay was taken over. */
    private fun continues(arrivalMicros: Long, iTowMs: Long): Boolean {
        val step = lastTowMs?.let { iTowMs - it }
        val low = lowest()
        val offset = arrivalMicros - iTowMs * MicrosPerMilli
        return step != null && step in 1..MaxStepMs && low != null && offset >= low - MaxOffsetDropMicros
    }

    /** The delay to stamp with: the window's lowest, unless that rose faster than the clocks can drift. */
    private fun lowest(): Long? {
        val inWindow = window.firstOrNull()?.second ?: return null
        return minOf(inWindow, risen ?: inWindow)
    }

    private fun observe(arrivalMicros: Long, iTowMs: Long) {
        lastTowMs = iTowMs
        val offset = arrivalMicros - iTowMs * MicrosPerMilli
        count++
        while (window.isNotEmpty() && window.last().second >= offset) window.removeLast()
        window.addLast(count to offset)
        while (window.first().first <= count - windowMessages) window.removeFirst()
        val rise = (arrivalMicros - risenAt).coerceAtLeast(0) * maxRiseMicrosPerSecond / MicrosPerSecond
        risen = risen?.let { minOf(it + rise, offset) } ?: offset
        risenAt = arrivalMicros
    }

    private fun clear() {
        window.clear()
        risen = null
    }

    /**
     * How much later than the lowest delay [arrivalMicros] came for time of week [iTowMs], in
     * microseconds: the backlog in the link (FBBD-218). Zero before the first message.
     */
    fun backlogMicros(arrivalMicros: Long, iTowMs: Long): Long =
        lowest()?.let { (arrivalMicros - iTowMs * MicrosPerMilli - it).coerceAtLeast(0) } ?: 0L

    fun reset() {
        clear()
        lastTowMs = null
    }
}
