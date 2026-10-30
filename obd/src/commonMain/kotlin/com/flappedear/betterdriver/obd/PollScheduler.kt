package com.flappedear.betterdriver.obd

/**
 * Decides which request the ELM327 reader sends next (FBBD-53). Pure logic with the clock passed
 * in; the reader (FBBD-15) calls [next], sends the request, then reports it with [completed].
 *
 * An ELM327 answers one request at a time, so the adapter's time is shared out. Channels with the same
 * [Request] share one request, and [groups] can put several requests into one (FBBD-270: several
 * mode 01 PIDs asked together, see [MultiPid]); a group is named by its first channel's request.
 * Requests are picked by stride scheduling: each request has a pass value that grows by 1/weight when it is sent, and the lowest pass goes next, so a [PollClass.FAST]
 * request is sent [fastWeight] times as often as a [PollClass.SLOW] one (a request is fast when any
 * of its channels is). Slow requests are also never sent more than [slowMaxHz] times a second: when
 * the bus is quick, temperatures do not need 5 Hz and that time goes to the fast channels. After a
 * slow request, every fast one goes before the next slow one (FBBD-284): temperatures that come due
 * together would otherwise hold engine speed back for all of them, 0.56 s every 2 s on a single-PID
 * adapter.
 *
 * A request that gets no usable answer [dropAfterMisses] times in a row (NO DATA, a negative response,
 * an equation with no value) is dropped, so a profile channel the car does not have stops costing time.
 * A dropped request is tried once more after [retryAfterMs], then after twice as long each time it
 * still gets nothing, up to [maxRetryAfterMs] (FBBD-382): one that stopped answering for a while,
 * like temperatures on 2026-10-29, comes back, and one the car never had costs one request in minutes.
 *
 * [predictedHz] says what each request should get for an observed per-request latency, and
 * [achievedHz] measures what it did get over the last [rateWindowMs].
 */
@Suppress("LongParameterList", "TooManyFunctions") // Rates and waits set by tests; one query per thing the reader asks.
class PollScheduler(
    channels: List<PidChannel>,
    private val fastWeight: Int = DEFAULT_FAST_WEIGHT,
    private val slowMaxHz: Double = DEFAULT_SLOW_MAX_HZ,
    private val dropAfterMisses: Int = DEFAULT_DROP_AFTER_MISSES,
    private val rateWindowMs: Long = DEFAULT_RATE_WINDOW_MS,
    private val retryAfterMs: Long = DEFAULT_RETRY_AFTER_MS,
    private val maxRetryAfterMs: Long = DEFAULT_MAX_RETRY_AFTER_MS,
    groups: List<List<PidChannel>> = channels.groupBy { it.request }.values.toList(),
) {
    private class Slot(val request: Request, val members: Set<Request>, val fast: Boolean) {
        var pass = 0.0
        var lastSentMs = Long.MIN_VALUE
        var misses = 0
        var dropped = false
        var answeredOnce = false

        /** When a dropped request is tried again, and how long it waits after that if it still misses. */
        var retryAtMs = Long.MAX_VALUE
        var retryWaitMs = 0L

        /** [members] got a value at [atMs]: polled again as usual. */
        fun answered(members: Collection<Request>, atMs: Long) {
            misses = 0
            answeredOnce = true
            dropped = false
            retryAtMs = Long.MAX_VALUE
            retryWaitMs = 0
            for (member in members) answers[member]?.addLast(atMs)
        }

        /** A fast request sent since the last slow one; every fast one is before the next slow one goes. */
        var sentSinceSlow = true
        val answers = members.associateWith { ArrayDeque<Long>() }
    }

    private val slots: List<Slot> = groups.filter { it.isNotEmpty() }.map { group ->
        val fast = group.any { it.pollClass == PollClass.FAST }
        Slot(group.first().request, group.mapTo(LinkedHashSet()) { it.request }, fast)
    }
    private val byRequest = slots.flatMap { slot -> slot.members.map { it to slot } }.toMap()
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
        // A dropped request whose wait is over is tried once, before the others.
        slots.firstOrNull { it.dropped && it.retryAtMs <= nowMs }?.let { return Next(it.request, nowMs) }
        val fastWaiting = live.any { it.fast && !it.sentSinceSlow }
        val due = live.filter { it.fast || dueAt(it) <= nowMs && !fastWaiting }
        if (due.isEmpty()) {
            val soonest = live.minBy { dueAt(it) }
            return Next(soonest.request, dueAt(soonest))
        }
        // A slow request that sat out its interval has fallen behind and goes as soon as it is due.
        return Next(due.minBy { it.pass }.request, nowMs)
    }

    /**
     * The dropped request tried again soonest, at its retry time; null when none is dropped. For a
     * reader that goes on without [next] (one listening to CAN broadcasts), so that requests dropped
     * all together come back too.
     */
    fun retry(): Next? =
        slots.filter { it.dropped }.minByOrNull { it.retryAtMs }?.let { Next(it.request, it.retryAtMs) }

    /**
     * [request] was sent at [sentMs] and its answer came back [latencyMs] later; [answered] is true
     * when at least one of its channels got a value. For a group, [answeredRequests] are the members
     * that got a value (all of them when not given). Says what became of the request, for the log.
     */
    fun completed(
        request: Request,
        sentMs: Long,
        latencyMs: Long,
        answered: Boolean,
        answeredRequests: Collection<Request>? = null,
    ): Outcome {
        val slot = byRequest[request] ?: return Outcome.Missed
        val retried = slot.dropped
        // A request back from being dropped starts level with the others, not ahead by the time it was out.
        val level = slots.filter { !it.dropped }.minOfOrNull { it.pass } ?: 0.0
        slot.pass = maxOf(slot.pass, level) + 1.0 / weightOf(slot)
        slot.lastSentMs = sentMs
        if (slot.fast) {
            slot.sentSinceSlow = true
        } else {
            slots.forEach { it.sentSinceSlow = false }
        }
        this.latencyMs = this.latencyMs?.let { it + LATENCY_SMOOTHING * (latencyMs - it) } ?: latencyMs.toDouble()
        val outcome = when {
            answered -> {
                slot.answered(answeredRequests ?: slot.members, sentMs + latencyMs)
                if (retried) Outcome.Back else Outcome.Answered
            }
            retried -> {
                slot.retryWaitMs = minOf(slot.retryWaitMs * 2, maxRetryAfterMs)
                Outcome.StillDropped
            }
            ++slot.misses >= dropAfterMisses -> {
                slot.dropped = true
                slot.retryWaitMs = retryAfterMs
                Outcome.Dropped
            }
            else -> Outcome.Missed
        }
        if (slot.dropped) slot.retryAtMs = sentMs + slot.retryWaitMs
        return outcome
    }

    /** Whether [request] answered before and has now got no usable answer [misses] times in a row. */
    fun stoppedAnswering(request: Request, misses: Int): Boolean =
        byRequest[request]?.let { it.answeredOnce && it.misses >= misses } == true

    /** How many times in a row [request] got no usable answer. */
    fun missesInARow(request: Request): Int = byRequest[request]?.misses ?: 0

    /** How long a dropped [request] waits before it is tried again, in ms; 0 when it is polled. */
    fun retryWaitMs(request: Request): Long = byRequest[request]?.takeIf { it.dropped }?.retryWaitMs ?: 0

    /** Answers per second [request] got over the [rateWindowMs] before [nowMs]. */
    fun achievedHz(request: Request, nowMs: Long): Double {
        val answers = byRequest[request]?.answers?.get(request) ?: return 0.0
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

    /** What became of a request: answered, missed, dropped, tried again and still dropped, or back. */
    enum class Outcome { Answered, Missed, Dropped, StillDropped, Back }

    companion object {
        const val DEFAULT_FAST_WEIGHT = 5
        const val DEFAULT_SLOW_MAX_HZ = 2.0
        const val DEFAULT_DROP_AFTER_MISSES = 5
        const val DEFAULT_RATE_WINDOW_MS = 5_000L
        const val DEFAULT_RETRY_AFTER_MS = 30_000L
        const val DEFAULT_MAX_RETRY_AFTER_MS = 300_000L
        private const val MS_PER_S = 1000.0
        private const val LATENCY_SMOOTHING = 0.2
    }
}
