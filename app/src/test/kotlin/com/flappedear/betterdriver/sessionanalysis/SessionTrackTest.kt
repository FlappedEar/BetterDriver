package com.flappedear.betterdriver.sessionanalysis

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTrackTest {
    private val line = TrackLine(GeoPoint(50.0, 19.0), GeoPoint(50.0001, 19.0))
    private val near = Track("near", "Near", GeoPoint(50.0, 19.0), startFinish = line)
    private val noLine = Track("noline", "No line", GeoPoint(50.0, 19.0))
    private val far = Track("far", "Far", GeoPoint(52.0, 21.0), startFinish = line)

    private fun session(events: List<RecorderEvent>, lat: Double = 50.001, laterLat: Double? = null) = SessionData(
        "s",
        SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL),
        SessionState.FINISHED,
        emptyMap(),
        listOfNotNull(
            SampleSet(2, listOf(ChannelValue(GnssChannels.LAT.id, lat), ChannelValue(GnssChannels.LON.id, 19.0))),
            SampleSet(1, listOf(ChannelValue(GnssChannels.LAT.id, 0.0), ChannelValue(GnssChannels.LON.id, 0.0))),
            laterLat?.let {
                SampleSet(3, listOf(ChannelValue(GnssChannels.LAT.id, it), ChannelValue(GnssChannels.LON.id, 19.0)))
            },
        ),
        events,
        null,
        0,
        0,
    )

    @Test
    fun `the saved track the session recorded is used`() {
        val events = listOf(RecorderEvent(5, "track", "saved far rev 3 Far · GP"))
        assertEquals("far", savedTrackId(events))
        assertEquals(far, trackFor(session(events), listOf(near, far)))
    }

    @Test
    fun `otherwise the nearest saved track with a line around the first fix`() {
        val events = listOf(RecorderEvent(5, "track", "circuit 12 Somewhere"))
        assertNull(savedTrackId(events))
        assertEquals(near, trackFor(session(events), listOf(noLine, far, near)))
    }

    @Test
    fun `no track with a line nearby, no track`() {
        assertNull(trackFor(session(emptyList(), lat = 40.0), listOf(near, far, noLine)))
    }

    @Test
    fun `a session that started away from the track takes the track it drove into`() {
        // Started 110 km away, then drove to the track (or the track was made from it afterwards).
        assertEquals(near, trackFor(session(emptyList(), lat = 49.0, laterLat = 50.001), listOf(far, noLine, near)))
    }
}
