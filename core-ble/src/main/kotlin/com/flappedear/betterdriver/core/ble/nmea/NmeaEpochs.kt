package com.flappedear.betterdriver.core.ble.nmea

/**
 * The sentences a receiver sent for one fix. [timeOfDayMs] is the UTC time shared by its RMC and
 * GGA (null if the receiver does not know the time yet). [arrivalMicros] is the recorder clock when
 * the first of them arrived. Any of the sentences can be missing: receivers differ in what they
 * send, and the user may have turned some off.
 */
data class NmeaEpoch(
    val timeOfDayMs: Long?,
    val arrivalMicros: Long,
    val rmc: Rmc? = null,
    val gga: Gga? = null,
    val vtg: Vtg? = null,
    val gsa: Gsa? = null,
)

/**
 * Groups sentences into epochs. RMC and GGA carry the fix time; a sentence with a new time starts a
 * new epoch and ends the previous one. As soon as an epoch has both RMC and GGA it is complete and
 * passed on without waiting for the next fix, so live displays are not a fix behind.
 *
 * VTG and GSA carry no time and join the open epoch. Receivers send them in different orders
 * (u-blox: RMC VTG GGA GSA; SiRF/MediaTek: GGA GSA RMC VTG), so one that arrives after its epoch
 * was already passed on is dropped; RMC and GGA hold everything the recorder needs from it.
 *
 * The first sentence of each kind wins: a receiver that sends both GPRMC and GNRMC for one fix
 * produces one epoch, not two. Not thread-safe.
 */
class NmeaEpochAssembler {
    private var open: NmeaEpoch? = null
    private var passedOn = false

    fun add(sentence: NmeaSentence, nowMicros: Long, onEpoch: (NmeaEpoch) -> Unit) {
        when (sentence) {
            is Rmc -> timed(sentence.timeOfDayMs, sentence, nowMicros, onEpoch)
            is Gga -> timed(sentence.timeOfDayMs, sentence, nowMicros, onEpoch)
            is Vtg, is Gsa -> if (!passedOn) open = open?.let { merge(it, sentence) }
        }
    }

    /** Forgets the open epoch without passing it on, e.g. after a reconnect. */
    fun reset() {
        open = null
        passedOn = false
    }

    private fun timed(time: Long?, sentence: NmeaSentence, nowMicros: Long, onEpoch: (NmeaEpoch) -> Unit) {
        val current = open
        if (current == null || !belongsTo(current, time, sentence)) {
            if (current != null && !passedOn) onEpoch(current)
            open = NmeaEpoch(time, nowMicros)
            passedOn = false
        } else if (passedOn) {
            // Another talker's copy of a sentence already in an epoch that went out.
            return
        }
        val merged = merge(checkNotNull(open), sentence)
        open = merged
        if (merged.rmc != null && merged.gga != null) {
            passedOn = true
            onEpoch(merged)
        }
    }

    /**
     * Same time, same epoch. Without a time (no fix yet) there is nothing to compare, so a second
     * sentence of a kind the epoch already has starts the next one.
     */
    private fun belongsTo(epoch: NmeaEpoch, time: Long?, sentence: NmeaSentence): Boolean = when {
        epoch.timeOfDayMs != time -> false
        time != null -> true
        sentence is Rmc -> epoch.rmc == null
        else -> epoch.gga == null
    }

    private fun merge(e: NmeaEpoch, s: NmeaSentence): NmeaEpoch = when (s) {
        is Rmc -> if (e.rmc == null) e.copy(rmc = s) else e
        is Gga -> if (e.gga == null) e.copy(gga = s) else e
        is Vtg -> if (e.vtg == null) e.copy(vtg = s) else e
        is Gsa -> if (e.gsa == null) e.copy(gsa = s) else e
    }
}
