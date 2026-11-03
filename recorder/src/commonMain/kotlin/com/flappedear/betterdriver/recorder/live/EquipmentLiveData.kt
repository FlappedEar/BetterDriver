package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.core.JvmSynchronized
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.time.TimeSource

/** One channel's newest value on an Equipment page's live data (FBBD-440). */
data class LiveChannel(val def: ChannelDef, val value: Double)

/** Something the device's source logged, such as a connect or a drop; [atMs] since the page opened. */
data class LiveEvent(val atMs: Long, val kind: String, val text: String)

/** What a device sent since its live data page opened, at one moment (FBBD-440). */
data class EquipmentLiveSnapshot(
    /** Each channel's newest value, by channel id. */
    val channels: List<LiveChannel>,
    /** Sample sets per second over the last few seconds; 0 before the first. */
    val rateHz: Double,
    /** Sample sets since the page opened. */
    val sets: Long,
    /** Milliseconds since the newest set, or null before the first. */
    val sinceLastMs: Long?,
    /** Milliseconds since the page opened. */
    val openMs: Long,
    /** The newest events, oldest first. */
    val events: List<LiveEvent>,
)

/**
 * The sample sink an Equipment page's live data hands to the device's own recorder source (Android)
 * or link (iOS) while no session records (FBBD-440): it keeps each channel's newest value, the rate
 * and the source's events, and nothing is written anywhere. The source is the same code that records
 * the device in a session, so what shows here is what a session would record.
 *
 * Synchronized on the JVM, where a source submits from its own thread and the page reads on the UI
 * one; on iOS the caller holds a lock around every call.
 */
class EquipmentLiveData(private val nowMs: () -> Long) : SampleSink {
    /** On the monotonic clock; the iPhone has no default arguments, so this is a constructor of its own. */
    constructor() : this(monotonicMs())

    private val openedAt = nowMs()
    private val defs = mutableMapOf<Int, ChannelDef>()
    private val latest = mutableMapOf<Int, Double>()
    private val arrivals = ArrayDeque<Long>()
    private val events = ArrayDeque<LiveEvent>()
    private var sets = 0L
    private var lastMs: Long? = null

    @JvmSynchronized
    override fun defineChannel(def: ChannelDef) {
        defs[def.id] = def
    }

    @JvmSynchronized
    override fun submit(set: SampleSet) {
        if (set.values.isEmpty()) return
        val now = nowMs()
        set.values.forEach { latest[it.channelId] = it.value }
        sets++
        lastMs = now
        arrivals.addLast(now)
        while (arrivals.isNotEmpty() && arrivals.first() < now - RateWindowMs) arrivals.removeFirst()
    }

    @JvmSynchronized
    override fun event(kind: String, text: String) {
        events.addLast(LiveEvent(nowMs() - openedAt, kind, text))
        while (events.size > MaxEvents) events.removeFirst()
    }

    override fun nowMicros(): Long = (nowMs() - openedAt) * MicrosPerMs

    @JvmSynchronized
    fun snapshot(): EquipmentLiveSnapshot {
        val now = nowMs()
        while (arrivals.isNotEmpty() && arrivals.first() < now - RateWindowMs) arrivals.removeFirst()
        // Between the oldest and the newest set of the last few seconds: a device that stopped falls to 0.
        val spanMs = if (arrivals.size < 2) 0L else arrivals.last() - arrivals.first()
        val rate = if (spanMs <= 0L) 0.0 else (arrivals.size - 1) * MsPerSecond / spanMs
        val channels = latest.keys.sorted().map { id ->
            LiveChannel(defs[id] ?: ChannelDef(id, "channel_$id", "", ""), latest.getValue(id))
        }
        return EquipmentLiveSnapshot(
            channels = channels,
            rateHz = rate,
            sets = sets,
            sinceLastMs = lastMs?.let { now - it },
            openMs = now - openedAt,
            events = events.toList(),
        )
    }

    /** Forgets the values, for a new connection (a session ended and the page connects again). */
    @JvmSynchronized
    fun reset() {
        latest.clear()
        arrivals.clear()
        sets = 0
        lastMs = null
    }

    private companion object {
        const val RateWindowMs = 5_000L
        const val MaxEvents = 6
        const val MicrosPerMs = 1_000L
        const val MsPerSecond = 1_000.0

        fun monotonicMs(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
