package com.flappedear.betterdriver.recording

import android.app.Application
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The live snapshot crosses from the recorder process to the UI process in a Bundle (FBBD-262). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecorderProtocolTest {
    private val live = LiveSnapshot(
        sessionId = "s1",
        startUtcMillis = 1_000,
        elapsedMicros = 5_000_000,
        fixes = 125,
        fixRateHz = 25,
        lastFixAgeMicros = 40_000,
        sats = 18,
        speedKmh = 142.0,
        storageLowBytes = null,
        stoppedReason = null,
        droppedSamples = 0,
        gCombined = 1.12,
        receiverRateHz = 25,
    )

    @Test
    fun `OBD and heart-rate values survive the trip to the UI process`() {
        val withChannels = live.copy(channels = mapOf("obd_coolant_temp" to 92.0, "heart_rate" to 142.0))
        assertEquals(withChannels, RecorderProtocol.fromBundle(RecorderProtocol.toBundle(withChannels)))
    }

    @Test
    fun `the car's position survives the trip for the map screen`() {
        val placed = live.copy(latitude = 50.123456789, longitude = 18.987654321)
        assertEquals(placed, RecorderProtocol.fromBundle(RecorderProtocol.toBundle(placed)))
    }

    @Test
    fun `a snapshot without car or body values reads back with none`() {
        assertEquals(live, RecorderProtocol.fromBundle(RecorderProtocol.toBundle(live)))
    }
}
