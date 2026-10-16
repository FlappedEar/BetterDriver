package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.FixAge
import com.flappedear.betterdriver.recorder.store.gnssFixAge

/** The last this long of driving is measured. */
private const val WindowMicros = 180_000_000L

/** Measured again after this much more. */
private const val EveryMicros = 30_000_000L

/**
 * How old the receiver's GNSS fix is when it arrives, measured while recording (FBBD-229): as
 * [gnssFixAgeMicros] does for a stored session, over the last [WindowMicros] of sets that carry both
 * the fix and the receiver's acceleration ([accelIds]), again every [EveryMicros]. [micros] is 0 until
 * a measurement succeeds (at least a minute of driving), then the latest one that did. [onChange] is
 * told each new outcome: a new age, or a new reason there is none ("on time", too little driving),
 * so the log shows a receiver whose fix is on time as well as one whose fix is late. Feed it from
 * one thread.
 */
class LiveFixAge(private val accelIds: Set<Int>, private val onChange: (FixAge) -> Unit = {}) {
    @Volatile
    var micros = 0L
        private set

    private val sets = ArrayDeque<SampleSet>()
    private var nextAt = Long.MIN_VALUE
    private var told: String? = null

    fun add(set: SampleSet) {
        val withFix = set.values.any { it.channelId == GnssChannels.SPEED_KMH.id } &&
            set.values.any { it.channelId in accelIds }
        if (!withFix) return
        sets.addLast(set)
        while (sets.first().tMicros < set.tMicros - WindowMicros) sets.removeFirst()
        if (nextAt == Long.MIN_VALUE) nextAt = set.tMicros + EveryMicros
        if (set.tMicros >= nextAt) {
            nextAt = set.tMicros + EveryMicros
            measure()
        }
    }

    private fun measure() {
        val age = gnssFixAge(sets.toList(), accelIds)
        age.micros?.let { micros = it }
        // The agreement figures change at every measurement; only a new age or reason is told.
        val outcome = age.micros?.toString() ?: age.text.substringBefore(" (")
        if (outcome != told) {
            told = outcome
            onChange(age)
        }
    }
}
