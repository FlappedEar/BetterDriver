package com.flappedear.betterdriver.recorder.fake

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/** The GNSS channels of a made-up receiver, marked as made up (FBBD-174): a demo measures nothing. */
val SimulatedGnssChannels = GnssChannels.ALL.map { it.copy(provenance = Provenance.SIMULATED) }

/**
 * Feeds a [GnssScript]'s fixes into [sink] as time passes (FBBD-373), for an app whose timer ticks
 * now and then, the iPhone's Demo track: [advance] sends every fix due by then, fix n at n / rateHz
 * seconds after [start], each stamped with the sink's clock as it is sent, as Android's FakeGnssSource
 * sends them from its own thread. A late tick catches up, a few seconds of fixes per tick at most;
 * fix numbers never skip.
 */
class ScriptFeed(private val script: GnssScript, private val sink: SampleSink) {
    private var startMicros = -1L
    private var next = 0L

    /** The fixes sent so far. */
    val sent: Long get() = next

    /** Defines the made-up channels; the first fix is due now. */
    fun start() {
        SimulatedGnssChannels.forEach(sink::defineChannel)
        startMicros = sink.nowMicros()
    }

    /** Sends the fixes due by the sink's clock now; returns how many it sent. */
    fun advance(): Int {
        if (startMicros < 0) return 0
        val elapsed = sink.nowMicros() - startMicros
        val due = elapsed * script.rateHz / MicrosPerSecond
        var count = 0
        // A few seconds per tick at most: after a long pause the rest follows on the next ticks.
        while (next <= due && count < script.rateHz * MaxCatchUpSeconds) {
            sink.submit(SampleSet(sink.nowMicros(), script.fix(next)))
            next++
            count++
        }
        return count
    }

    private companion object {
        const val MicrosPerSecond = 1_000_000L
        const val MaxCatchUpSeconds = 5
    }
}
