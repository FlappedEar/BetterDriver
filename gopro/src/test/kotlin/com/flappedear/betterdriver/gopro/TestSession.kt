package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import kotlin.math.sin

/** A synthetic track session: 25 Hz GNSS with a lap-like speed trace. */
object TestSession {
    const val StartUtcMillis = 1_760_180_000_000L

    /** Speed in m/s at a UTC instant; the same function drives the fake camera GPS. */
    fun speedAt(utcMillis: Long): Double {
        val t = utcMillis / 1000.0
        return 30 + 12 * sin(t / 4.0) + 5 * sin(t / 1.7)
    }

    fun data(seconds: Int): SessionData {
        val sets = List(seconds * 25) { i ->
            val tUs = i * 40_000L
            val utc = StartUtcMillis + tUs / 1000
            SampleSet(tUs, listOf(
                ChannelValue(GnssChannels.UTC_MS.id, utc.toDouble()),
                ChannelValue(GnssChannels.SPEED_KMH.id, speedAt(utc) * 3.6),
            ))
        }
        return SessionData(
            id = "s1",
            info = SessionInfo(StartUtcMillis),
            state = SessionState.FINISHED,
            channels = GnssChannels.ALL.associateBy { it.id },
            sampleSets = sets,
            events = emptyList(),
            end = null,
            truncatedBytes = 0,
            skippedBytes = 0,
        )
    }
}
