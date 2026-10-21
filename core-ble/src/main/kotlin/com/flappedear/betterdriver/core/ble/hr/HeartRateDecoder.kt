package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.log.HeartRateLogIds

/** Heart rate monitor channels. Ids 400 to 499 are reserved for heart rate monitors. */
object HeartRateChannels {
    const val SOURCE = "heart_rate"

    val HEART_RATE = ChannelDef(HeartRateLogIds.CHANNEL, HeartRateLogIds.NAME, "bpm", SOURCE, 0)

    /** One value per beat, stamped at the beat (see [HeartRateDecoder]). */
    val RR_INTERVAL = ChannelDef(401, "hr_rr_interval", "ms", SOURCE, 1)

    /** Accumulated since the monitor last reset it; saturates at 65,535 kJ. */
    val ENERGY = ChannelDef(402, "hr_energy", "kJ", SOURCE, 0)

    val ALL = listOf(HEART_RATE, RR_INTERVAL, ENERGY)
}

/** Event kinds the heart rate source writes into the session. */
object HeartRateEvents {
    /** Text is "address=<address> name=<name>", written once at start. */
    const val DEVICE = HeartRateLogIds.DEVICE

    /** Text is the device address. */
    const val CONNECTED = "hr_connected"

    /** Text is the reason. */
    const val DISCONNECTED = HeartRateLogIds.DISCONNECTED

    /**
     * Skin contact found or lost, for monitors that report it, and lost while the heart rate is not
     * plausible ([PlausibleBpm]). Text is "detected" or "lost".
     */
    const val CONTACT = HeartRateLogIds.CONTACT

    /** The first heart rate of a run outside [PlausibleBpm]. Text is "bpm=<value> flags=0x<flags>". */
    const val IMPLAUSIBLE = HeartRateLogIds.IMPLAUSIBLE

    /** No plausible heart rate early in the session: the monitor is off until it ends. Text is the reason. */
    const val OFF = HeartRateLogIds.OFF
}

/**
 * Heart rates a driver can have. A strap that is not worn can still send: one lying on a cable
 * showed 24 bpm on 21 Oct 2026 (FBBD-285), while worn drives on 20 Oct ranged 76 to 118 bpm.
 * Values outside are noise, handled like a value without skin contact.
 */
val PlausibleBpm = 30..230

private const val MicrosPerMs = 1_000.0
private const val HexRadix = 16

/**
 * Turns Heart Rate Measurement notifications into sample sets, stamped with the recorder clock.
 * The heart rate and energy go in one set per notification, stamped on arrival. While a monitor
 * that reports skin contact says it has none, or the heart rate is outside [PlausibleBpm], the
 * heart rate and RR-Intervals are left out (they are not meaningful).
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
    private var inNoise = false

    /** Values decoded since the decoder was created. */
    @Volatile
    var decoded = 0L
        private set

    /** Values dropped because they were shorter than their flags say. */
    @Volatile
    var truncated = 0L
        private set

    /** The last heart rate stored, or null before the first or since a [reset]. */
    @Volatile
    var lastBpm: Int? = null
        private set

    /** When [lastBpm] arrived, on the recorder clock. */
    @Volatile
    var lastBpmMicros = 0L
        private set

    /** The monitor's last notification said it has no skin contact, or its heart rate was not plausible. */
    @Volatile
    var noContact = false
        private set

    /** Heart rates left out because they were outside [PlausibleBpm]. */
    @Volatile
    var implausible = 0L
        private set

    /** A plausible heart rate with skin contact arrived at least once, reconnects included. */
    @Volatile
    var everMeasured = false
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
        // A value the strap itself flags as without contact is not counted as implausible.
        val noise = m.contact != SensorContact.NOT_DETECTED && m.bpm !in PlausibleBpm
        if (noise) {
            val flags = m.flags.toString(HexRadix).padStart(2, '0')
            if (!inNoise) sink.event(HeartRateEvents.IMPLAUSIBLE, "bpm=${m.bpm} flags=0x$flags")
            implausible++
        }
        inNoise = noise
        val contact = if (noise) SensorContact.NOT_DETECTED else m.contact
        contactChanged(contact)
        noContact = contact == SensorContact.NOT_DETECTED
        if (!noContact) {
            lastBpm = m.bpm
            lastBpmMicros = arrival
            everMeasured = true
            rrIntervals(m.rrMs, arrival)
        }
        val values = buildList {
            if (!noContact) add(ChannelValue(HeartRateChannels.HEART_RATE.id, m.bpm.toDouble()))
            m.energyKj?.let { add(ChannelValue(HeartRateChannels.ENERGY.id, it.toDouble())) }
        }
        if (values.isNotEmpty()) sink.submit(SampleSet(arrival, values))
    }

    /** Forgets the contact state from before a reconnect. RR stamps stay in order across it. */
    @Synchronized
    fun reset() {
        contact = null
        noContact = false
        inNoise = false
        lastBpm = null
    }

    private fun contactChanged(now: SensorContact) {
        val had = contact
        contact = now
        if (now == had || now == SensorContact.NOT_SUPPORTED) return
        if (had == null && now == SensorContact.DETECTED) return
        val text = if (now == SensorContact.DETECTED) "detected" else HeartRateLogIds.CONTACT_LOST
        sink.event(HeartRateEvents.CONTACT, text)
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
