package com.flappedear.betterdriver.obd

/**
 * Decides which request the ELM327 reader sends next (FBBD-53). Pure logic with the clock passed
 * in; the reader (FBBD-15) calls [next], sends the request, then reports it with [completed].
 *
 * An ELM327 answers one request at a time, so the adapter's time is shared out. Channels with the same
 * [Request] share one request. Requests are picked by stride scheduling: each request has a pass
 * value that grows by 1/weight when it is sent, and the lowest pass goes next, so a [PollClass.FAST]
 * request is sent [fastWeight] times as often as a [PollClass.SLOW] one (a request is fast when any
 * of its channels is). Slow requests are also never sent more than [slowMaxHz] times a second: when
 * the bus is quick, temperatures do not need 5 Hz and that time goes to the fast channels.
 *
 * A request that gets no usable answer [dropAfterMisses] times in a row (NO DATA, a negative response,
 * an equation with no value) is dropped, so a profile channel the car does not have stops costing time.
 *
 * [predictedHz] says what each request should get for an observed per-request latency, and
 * [achievedHz] measures what it did get over the last [rateWindowMs].
 */
class PollScheduler(
    channels: List<PidChannel>,
    private val fastWeight: Int = DEFAULT_FAST_WEIGHT,
    private val slowMaxHz: Double = DEFAULT_SLOW_MAX_HZ,
    private val dropAfterMisses: Int = DEFAULT_DROP_AFTER_MISSES,
    private val rateWindowMs: Long = DEFAULT_RATE_WINDOW_MS,
) {
    private class Slot(val request: Request, val fast: Boolean) {
        var pass = 0.0
        var lastSentMs = Long.MIN_VALUE
        var misses = 0
        var dropped = false
        val answers = ArrayDeque<Long>()
    }

    private val slots: List<Slot> = channels.groupBy { it.request }.map { (request, group) ->
        Slot(request, group.any { it.pollClass == PollClass.FAST })
    }
    private val byRequest = slots.associateBy { it.request }
    private val slowIntervalMs = (MS_PER_S / slowMaxHz).toLong()

    /** The latest per-request latency reported to [completed], smoothed; null before the first. */
    var latencyMs: Double? = null
        private set

    /** The requests still polled, in the order given. */
    val active: List<Request> get() = slots.filter { !it.dropped }.map { it.request }

    /**
     * The request to send at [nowMs], and the earliest time to send it ([Next.notBeforeMs] is after
     * [nowMs] only when every remaining request is a slow one that is not due yet). Null when every
     * request has been dropped.
     */
    @Suppress("ReturnCount")
    fun next(nowMs: Long): Next? {
        val live = slots.filter { !it.dropped }
        if (live.isEmpty()) return null
        val due = live.filter { it.fast || dueAt(it) <= nowMs }
        if (due.isEmpty()) {
            val soonest = live.minBy { dueAt(it) }
            return Next(soonest.request, dueAt(soonest))
        }
        // A slow request that sat out its interval has fallen behind and goes as soon as it is due.
        return Next(due.minBy { it.pass }.request, nowMs)
    }

    /**
     * [request] was sent at [sentMs] and its answer came back [latencyMs] later; [answered] is true
     * when at least one of its channels got a value.
     */
    fun completed(request: Request, sentMs: Long, latencyMs: Long, answered: Boolean) {
        val slot = byRequest[request] ?: return
        slot.pass += 1.0 / weightOf(slot)
        slot.lastSentMs = sentMs
        this.latencyMs = this.latencyMs?.let { it + LATENCY_SMOOTHING * (latencyMs - it) } ?: latencyMs.toDouble()
        if (answered) {
            slot.misses = 0
            slot.answers.addLast(sentMs + latencyMs)
        } else if (++slot.misses >= dropAfterMisses) {
            slot.dropped = true
        }
    }

    /** Answers per second [request] got over the [rateWindowMs] before [nowMs]. */
    fun achievedHz(request: Request, nowMs: Long): Double {
        val answers = byRequest[request]?.answers ?: return 0.0
        while (answers.isNotEmpty() && answers.first() <= nowMs - rateWindowMs) answers.removeFirst()
        return answers.size * MS_PER_S / rateWindowMs
    }

    /** [achievedHz] for one channel: the rate of its request. */
    fun achievedHz(channel: PidChannel, nowMs: Long): Double = achievedHz(channel.request, nowMs)

    /**
     * The rate each active request should get when every request takes [latencyMs]: the adapter's
     * 1000/latency requests a second shared by weight, with slow requests capped at [slowMaxHz] and
     * what they leave going to the fast ones.
     */
    fun predictedHz(latencyMs: Double): Map<Request, Double> {
        val live = slots.filter { !it.dropped }
        if (live.isEmpty() || latencyMs <= 0) return emptyMap()
        var budget = MS_PER_S / latencyMs
        val rates = HashMap<Request, Double>()
        var open = live
        while (open.isNotEmpty()) {
            val totalWeight = open.sumOf { weightOf(it) }
            val capped = open.filter { !it.fast && budget * weightOf(it) / totalWeight > slowMaxHz }
            if (capped.isEmpty()) {
                open.forEach { rates[it.request] = budget * weightOf(it) / totalWeight }
                break
            }
            capped.forEach { rates[it.request] = slowMaxHz }
            budget -= capped.size * slowMaxHz
            open = open - capped.toSet()
        }
        return live.associate { it.request to rates.getValue(it.request) }
    }

    private fun weightOf(slot: Slot): Int = if (slot.fast) fastWeight else 1

    private fun dueAt(slot: Slot): Long =
        if (slot.lastSentMs == Long.MIN_VALUE) Long.MIN_VALUE else slot.lastSentMs + slowIntervalMs

    /** Send [request] at [notBeforeMs]. */
    data class Next(val request: Request, val notBeforeMs: Long)

    companion object {
        const val DEFAULT_FAST_WEIGHT = 5
        const val DEFAULT_SLOW_MAX_HZ = 2.0
        const val DEFAULT_DROP_AFTER_MISSES = 5
        const val DEFAULT_RATE_WINDOW_MS = 5_000L
        private const val MS_PER_S = 1000.0
        private const val LATENCY_SMOOTHING = 0.2
    }
}
