package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GnssSecondLogTest {
    private val start = 1_760_000_000_000L
    private val lines = mutableListOf<String>()
    private val log = GnssSecondLog("s1", start) { lines += it }

    /** A fix at receiver time [utcOffsetMs] after the start, arriving [lagUs] later on the session clock. */
    private fun fix(utcOffsetMs: Long, lagUs: Long = 30_000, lat: Double = 50.0, kmh: Double = 0.0) = SampleSet(
        utcOffsetMs * 1_000 + lagUs,
        listOf(
            ChannelValue(GnssChannels.UTC_MS.id, (start + utcOffsetMs).toDouble()),
            ChannelValue(GnssChannels.LAT.id, lat),
            ChannelValue(GnssChannels.LON.id, 10.0),
            ChannelValue(GnssChannels.SPEED_KMH.id, kmh),
        ),
    )

    private fun seconds() = lines.map { assertNotNull(GnssSecond.parse(it)) }

    @Test
    fun countsSamplesAndMissingOnesPerSecond() {
        (0 until 50).filter { it != 30 && it != 31 }.forEach { log.onSamples(fix(it * 40L)) }
        log.onStatus(RecorderStatus.Stopped("user"))
        val s = seconds()
        assertEquals(listOf(0L, 1L), s.map { it.second })
        assertEquals(25, s[0].samples)
        assertEquals(23, s[1].samples)
        assertEquals(2, s[1].missing)
        assertEquals(40L, s[1].stepMillis)
        assertEquals(120L, s[1].maxRxGapMillis)
        assertEquals(0, s[1].longGaps)
        assertEquals(30_000L, s[0].lagMicros)
    }

    @Test
    fun aStepAcrossALinkDropIsNoGap() {
        (0 until 10).forEach { log.onSamples(fix(it * 40L)) }
        log.onEvent(RecorderEvent(500_000, "racebox_disconnected", ""))
        (100 until 110).forEach { log.onSamples(fix(it * 40L)) }
        log.onStatus(RecorderStatus.Stopped("user"))
        val after = seconds().last()
        assertTrue(after.afterDrop)
        assertEquals(0, after.missing)
        assertEquals(0, after.longGaps)
    }

    @Test
    fun aLongStepIsAGap() {
        (0 until 10).forEach { log.onSamples(fix(it * 40L)) }
        (20 until 30).forEach { log.onSamples(fix(it * 40L)) }
        log.onStatus(RecorderStatus.Stopped("user"))
        assertEquals(1, seconds().sumOf { it.longGaps })
        assertEquals(440L, seconds().mapNotNull { it.maxRxGapMillis }.max())
    }

    @Test
    fun keepsThePositionSpreadOfTheSecond() {
        // Fixes 1 m north and south of 50° N in turn: 1 m from their mean.
        val dLat = 1.0 / 111_195.0
        (0 until 25).forEach { log.onSamples(fix(it * 40L, lat = 50.0 + if (it % 2 == 0) dLat else -dLat)) }
        log.onStatus(RecorderStatus.Stopped("user"))
        val s = seconds().single()
        assertEquals(1.0, s.varianceM2!!, 0.01)
        assertEquals(50.0, s.lat!!, 1e-6)
        assertEquals(0.0, s.kmhMax!!, 0.0)
    }

    @Test
    fun readsBackWhatItWrites() {
        val s = GnssSecond(
            3, 3_000_100, 24, 24, 1, 40, 0, 80, 95_000, true, 10, 990, 31_000, 50.1234567, -1.5, 0.25, 87.5, 80.0, 95.0,
        )
        assertEquals(s, GnssSecond.parse(s.format()))
    }
}
