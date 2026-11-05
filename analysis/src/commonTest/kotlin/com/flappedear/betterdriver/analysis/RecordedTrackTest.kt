package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.recordedTrack
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The track a shared session is sent with (FBBD-393): the one it recorded comes from its events alone,
 * so the share reads no samples; only a session that recorded none needs them, as [trackFor] does.
 */
class RecordedTrackTest {
    private val line = TrackLine(GeoPoint(50.0, 19.0), GeoPoint(50.0001, 19.0))
    private val near = Track("near", "Near", GeoPoint(50.0, 19.0), startFinish = line)
    private val far = Track("far", "Far", GeoPoint(52.0, 21.0), startFinish = line)
    private val noLine = Track("noline", "No line", GeoPoint(50.0, 19.0))

    private fun session(events: List<RecorderEvent>, samples: Boolean) = SessionData(
        "s",
        SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL),
        SessionState.FINISHED,
        emptyMap(),
        if (samples) {
            val fix = listOf(ChannelValue(GnssChannels.LAT.id, 50.001), ChannelValue(GnssChannels.LON.id, 19.0))
            listOf(SampleSet(1, fix))
        } else {
            emptyList()
        },
        events,
        null,
        0,
        0,
    )

    @Test
    fun theRecordedTrackIsFoundWithoutSamples() {
        val events = listOf(RecorderEvent(5, "track", "saved far rev 3 Far · GP"))
        assertEquals(far, recordedTrack(session(events, samples = false), listOf(near, far)))
        // The same track the review times it with, samples read.
        assertEquals(trackFor(session(events, samples = true), listOf(near, far)), far)
    }

    @Test
    fun aRecordedTrackWithoutALineOrNoLongerSavedIsNone() {
        val noLineEvent = listOf(RecorderEvent(5, "track", "saved noline rev 1 X"))
        val goneEvent = listOf(RecorderEvent(5, "track", "saved gone rev 1 X"))
        assertNull(recordedTrack(session(noLineEvent, samples = false), listOf(noLine)))
        assertNull(recordedTrack(session(goneEvent, samples = false), listOf(near)))
    }

    @Test
    fun aSessionThatRecordedNoTrackNeedsItsSamples() {
        val events = listOf(RecorderEvent(5, "track", "circuit 12 Somewhere"))
        assertNull(recordedTrack(session(events, samples = false), listOf(near, far)))
        assertEquals(near, trackFor(session(events, samples = true), listOf(far, near)))
    }
}
