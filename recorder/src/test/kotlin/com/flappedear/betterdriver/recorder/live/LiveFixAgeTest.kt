package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FBBD-229: the fix age measured while recording, for the live coach. */
class LiveFixAgeTest {
    private val accelX = 110

    /** RaceBox-like set [i] at 25 Hz: the fix taken [ageSeconds] before the stamp, the acceleration at it. */
    private fun set(i: Int, ageSeconds: Double): SampleSet {
        val t = 1.0 + i * 0.04
        val speed = 20.0 + 8.0 * sin(2 * PI * (t - ageSeconds) / 20.0)
        val accel = 8.0 * 2 * PI / 20.0 * cos(2 * PI * t / 20.0) / 9.80665
        return SampleSet(
            (t * 1_000_000).toLong(),
            listOf(
                ChannelValue(GnssChannels.SPEED_KMH.id, speed * 3.6),
                ChannelValue(accelX, accel + 0.02 * sin(i * 1.7)),
            ),
        )
    }

    @Test
    fun measuresTheAgeOnceThereIsAMinuteOfDrivingAndTellsEachNewValue() {
        val told = mutableListOf<Long>()
        val age = LiveFixAge(setOf(accelX)) { told += it }
        (0 until 25 * 50).forEach { age.add(set(it, 0.8)) }
        assertEquals(0L, age.micros, "under a minute: not known yet")
        (25 * 50 until 25 * 200).forEach { age.add(set(it, 0.8)) }
        assertTrue(abs(age.micros - 800_000) <= 40_000, "fix age ${age.micros} µs, expected 0.8 s")
        assertEquals(listOf(age.micros), told.distinct())
    }

    @Test
    fun setsWithoutTheAccelerometerAreIgnored() {
        val age = LiveFixAge(setOf(accelX))
        (0 until 25 * 200).forEach { i ->
            val s = set(i, 0.8)
            age.add(SampleSet(s.tMicros, s.values.filter { it.channelId != accelX }))
        }
        assertEquals(0L, age.micros)
    }
}
