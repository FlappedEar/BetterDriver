package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.math.roundToInt

/** What the heart-rate check needs to know of core-ble's heart rate source (FBBD-119), which this module cannot see. */
object HeartRateLogIds {
    /** core-ble's HeartRateChannels.HEART_RATE. */
    const val CHANNEL = 400

    /** That channel's name, as the session, the VBO export and the analysis know it. */
    const val NAME = "heart_rate"

    /** core-ble's HeartRateEvents.DEVICE: "address=<address> name=<name>", once at start. */
    const val DEVICE = "hr_device"

    /** core-ble's HeartRateEvents.DISCONNECTED: the link to the monitor dropped. */
    const val DISCONNECTED = "hr_disconnected"

    /** core-ble's HeartRateEvents.CONTACT: "detected" or "lost". */
    const val CONTACT = "hr_contact"
    const val CONTACT_LOST = "lost"

    /** core-ble's HeartRateEvents.IMPLAUSIBLE: a heart rate outside what a driver can have, left out. */
    const val IMPLAUSIBLE = "hr_implausible"

    /** core-ble's HeartRateEvents.OFF: no plausible heart rate early in the session, monitor off. */
    const val OFF = "hr_off"
}

/**
 * The heart rate samples of up to 10 s of a recording, as the continuous log holds them (FBBD-225):
 * enough to work out the rate, the gaps and the reconnects of a heart-rate monitor without the
 * session file. One line per window with samples, so a strap adds about 6 lines a minute.
 *
 * [tMicros] and [lastMicros] are the session times of the window's first and last heart rate; [samples]
 * how many came. [maxGapMicros] is the longest wait between two heart rates ending in this window,
 * leaving out waits across a link drop or a lost skin contact (those are the drop's and the strap's,
 * not the link's). [backMicros] is the first heart rate after a link drop, when one came in this
 * window. [bpmMin], [bpmMax] and [bpmSum] are over the window's samples.
 */
data class HeartRateWindow(
    val tMicros: Long,
    val lastMicros: Long,
    val samples: Int,
    val maxGapMicros: Long?,
    val backMicros: Long?,
    val bpmMin: Int,
    val bpmMax: Int,
    val bpmSum: Long,
) {
    /** The line's message: `key=value` pairs; a value not known is left out. */
    fun format(): String = listOf(
        "t_us" to tMicros, "last_us" to lastMicros, "n" to samples, "gap_us" to maxGapMicros,
        "back_us" to backMicros, "bpm_min" to bpmMin, "bpm_max" to bpmMax, "bpm_sum" to bpmSum,
    ).filter { it.second != null }.joinToString(" ") { (k, v) -> "$k=$v" }

    companion object {
        /** A [CheckLog.HEART_RATE] line's message read back; null when it is not one. */
        fun parse(message: String): HeartRateWindow? {
            val f = CheckLog.fields(message)
            val v = Required.associateWith { f[it]?.toLongOrNull() ?: return null }
            return HeartRateWindow(
                tMicros = v.getValue("t_us"),
                lastMicros = v.getValue("last_us"),
                samples = v.getValue("n").toInt(),
                maxGapMicros = f["gap_us"]?.toLongOrNull(),
                backMicros = f["back_us"]?.toLongOrNull(),
                bpmMin = v.getValue("bpm_min").toInt(),
                bpmMax = v.getValue("bpm_max").toInt(),
                bpmSum = v.getValue("bpm_sum"),
            )
        }

        /** The fields every line has. */
        private val Required = listOf("t_us", "last_us", "n", "bpm_min", "bpm_max", "bpm_sum")

        /** The windows of [sets] and [events] of a stored session, as [HeartRateLog] logs them. */
        fun of(sets: List<SampleSet>, events: List<RecorderEvent>): List<HeartRateWindow> {
            val out = mutableListOf<HeartRateWindow>()
            val log = HeartRateLog("", write = { line -> parse(line)?.let { out += it } })
            val byTime = sets.filter { s -> s.values.any { it.channelId == HeartRateLogIds.CHANNEL } }
                .map { it.tMicros to it } + events.map { it.tMicros to it }
            // A sample and an event at the same time: the sample first. A drop is logged after the data
            // before it, and data after a reconnect only comes later.
            byTime.sortedWith(compareBy({ it.first }, { if (it.second is SampleSet) 0 else 1 })).forEach { (_, x) ->
                if (x is SampleSet) log.onSamples(x) else log.onEvent(x as RecorderEvent)
            }
            log.onStatus(RecorderStatus.Stopped("end"))
            return out
        }
    }
}

/**
 * Writes a [HeartRateWindow] line to the continuous log for each 10 s of [sessionId] that had heart
 * rate samples (FBBD-225). Runs on the recorder's listener thread and keeps one window in memory.
 * [write] is the log (a test hook).
 */
class HeartRateLog(
    private val sessionId: String,
    private val write: (String) -> Unit = { DebugLog.forSession(sessionId, CheckLog.HEART_RATE, it) },
) : RecorderListener {
    private var current: Builder? = null
    private var lastT: Long? = null
    private var interrupted = false
    private var dropped = false

    override fun onSamples(set: SampleSet) {
        val bpm = set.values.firstOrNull { it.channelId == HeartRateLogIds.CHANNEL }?.value
            ?.takeIf { it.isFinite() }?.roundToInt() ?: return
        val window = set.tMicros / WindowMicros
        val open = current
        val b = if (open == null || open.window != window) {
            open?.let { write(it.build().format()) }
            Builder(window, set.tMicros).also { current = it }
        } else {
            open
        }
        val previous = lastT
        if (previous != null && !interrupted) b.maxGap = maxOf(b.maxGap ?: 0L, set.tMicros - previous)
        if (dropped && b.back == null) b.back = set.tMicros
        interrupted = false
        dropped = false
        lastT = set.tMicros
        b.last = set.tMicros
        b.samples++
        b.min = minOf(b.min, bpm)
        b.max = maxOf(b.max, bpm)
        b.sum += bpm
    }

    override fun onEvent(event: RecorderEvent) {
        when {
            event.kind == HeartRateLogIds.DISCONNECTED -> {
                interrupted = true
                dropped = true
            }
            event.kind == HeartRateLogIds.CONTACT && event.text == HeartRateLogIds.CONTACT_LOST -> interrupted = true
        }
    }

    override fun onStatus(status: RecorderStatus) {
        if (status is RecorderStatus.Stopped) {
            current?.let { write(it.build().format()) }
            current = null
        }
    }

    private class Builder(val window: Long, val first: Long) {
        var last = first
        var samples = 0
        var maxGap: Long? = null
        var back: Long? = null
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var sum = 0L

        fun build() = HeartRateWindow(first, last, samples, maxGap, back, min, max, sum)
    }

    private companion object {
        const val WindowMicros = 10_000_000L
    }
}
