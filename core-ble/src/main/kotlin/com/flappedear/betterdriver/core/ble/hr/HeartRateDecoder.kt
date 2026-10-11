package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/** Heart rate monitor channels. Ids 400 to 499 are reserved for heart rate monitors. */
object HeartRateChannels {
    const val SOURCE = "heart_rate"

    val HEART_RATE = ChannelDef(400, "heart_rate", "bpm", SOURCE, 0)

    /** One value per beat, stamped at the beat (see [HeartRateDecoder]). */
    val RR_INTERVAL = ChannelDef(401, "hr_rr_interval", "ms", SOURCE, 1)

    /** Accumulated since the monitor last reset it; saturates at 65,535 kJ. */
    val ENERGY = ChannelDef(402, "hr_energy", "kJ", SOURCE, 0)

    val ALL = listOf(HEART_RATE, RR_INTERVAL, ENERGY)
}

/** Event kinds the heart rate source writes into the session. */
object HeartRateEvents {
    /** Text is "address=<address> name=<name>", written once at start. */
    const val DEVICE = "hr_device"

    /** Text is the device address. */
    const val CONNECTED = "hr_connected"

    /** Text is the reason. */
    const val DISCONNECTED = "hr_disconnected"

    /** Skin contact found or lost, for monitors that report it. Text is "detected" or "lost". */
    const val CONTACT = "hr_contact"
}

private const val MicrosPerMs = 1_000.0

/**
 * Turns Heart Rate Measurement notifications into sample sets, stamped with the recorder clock.
 * The heart rate and energy go in one set per notification, stamped on arrival. While a monitor
 * that reports skin contact says it has none, the heart rate is left out (it is not meaningful).
 *
 * RR-Intervals, when sent, each get their own set, stamped at the beat that ended them: the
 * newest at arrival, each older one earlier by the intervals after it. A stamp is never before
 * the previous interval's, nor before the session start, nor after arrival, so the channel stays
 * in order even if the monitor's and the phone's clocks drift apart. Synchronized, so a
 * reconnect can [reset] it from another thread.
 */
class HeartRateDecoder(private val sink: SampleSink) {
    private var contact: SensorContact? = null
    private var lastRrMicros = Long.MIN_VALUE

    /** Values decoded since the decoder was created. */
    @Volatile
    var decoded = 0L
        private set

    /** Values dropped because they were shorter than their flags say. */
    @Volatile
    var truncated = 0L
        private set

    fun defineChannels() {
        HeartRateChannels.ALL.forEach(sink::defineChannel)
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        val arrival = sink.nowMicros()
        val m = HeartRateMeasurement.parse(bytes)
        if (m == null) {
            truncated++
            return
        }
        decoded++
        contactChanged(m.contact)
        rrIntervals(m.rrMs, arrival)
        val values = buildList {
            if (m.contact != SensorContact.NOT_DETECTED) {
                add(ChannelValue(HeartRateChannels.HEART_RATE.id, m.bpm.toDouble()))
            }
            m.energyKj?.let { add(ChannelValue(HeartRateChannels.ENERGY.id, it.toDouble())) }
        }
        if (values.isNotEmpty()) sink.submit(SampleSet(arrival, values))
    }

    /** Forgets the contact state from before a reconnect. RR stamps stay in order across it. */
    @Synchronized
    fun reset() {
        contact = null
    }

    private fun contactChanged(now: SensorContact) {
        val had = contact
        contact = now
        if (now == had || now == SensorContact.NOT_SUPPORTED) return
        if (had == null && now == SensorContact.DETECTED) return
        sink.event(HeartRateEvents.CONTACT, if (now == SensorContact.DETECTED) "detected" else "lost")
    }

    private fun rrIntervals(rrMs: List<Double>, arrival: Long) {
        var after = 0.0
        val stamps = rrMs.indices.reversed().map { i ->
            val t = arrival - (after * MicrosPerMs).toLong()
            after += rrMs[i]
            t
        }.reversed()
        rrMs.forEachIndexed { i, rr ->
            val t = maxOf(stamps[i], lastRrMicros + 1, 0L).coerceAtMost(arrival)
            lastRrMicros = t
            sink.submit(SampleSet(t, listOf(ChannelValue(HeartRateChannels.RR_INTERVAL.id, rr))))
        }
    }
}
