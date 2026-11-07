@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.log.IosLog
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * The first outing at a track not in the app (Moto Park Kraków, 8 Nov 2026) on the iPhone: session 1
 * learns the track with no saved track and no coach; the driver makes the track from it (Tracks, New
 * track here, start/finish placed on session 1's lap in the editor); session 2 is timed on that track
 * and coached against session 1's laps, as Android's CoachLibrary matches a session by its place (FBBD-469).
 */
class NewTrackCoachTest {
    private val base = NSTemporaryDirectory() + "new-track-coach-test-" + NSUUID().UUIDString
    private val sessions = "$base/sessions"
    private val tracks = "$base/tracks"
    private val references = "$base/coach/references"
    private val lines = "$base/coach/lines"
    private val gnss = FakeGnss()
    private val gate = gnss.startFinish

    private class Speaker : CoachSpeaker {
        val said = mutableListOf<String>()
        override val speaking = false

        override fun say(text: String, speech: CoachSpeech): Boolean {
            said += text
            speech.started("headset", "bt_a2dp", clip = false)
            speech.ended("done")
            return true
        }
    }

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(tracks, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        IosLog.sink = null
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    /** Three and a half laps of 40 s at 25 Hz; coached with [speaker] when one is given. */
    private fun drive(speaker: Speaker?): LiveSession {
        val info = SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL)
        val session = LiveSession.start(sessions, tracks, CircuitList.Empty, info, receiverHasG = false)
        val settings = coachSettings(polish = false, audioDelayMillis = 0)
        if (speaker != null) session.startCoach(sessions, references, lines, settings, 0, "bt_a2dp", speaker)
        for (n in 0L..(25 * 140)) session.submit(SampleSet(n * 40_000, gnss.fix(n)))
        session.awaitCoach()
        session.awaitCoach()
        return session
    }

    @Test
    fun aTrackMadeAfterTheFirstSessionTimesAndCoachesTheSecond() {
        // Session 1: no saved track, no coach; live timing places a provisional line (FBBD-335).
        val first = drive(speaker = null)
        first.stop()
        val firstEvents = SessionStore(sessions).read(first.id).events
        assertEquals("unknown", firstEvents.single { it.kind == TrackEvent.KIND }.text)
        assertNotNull(TrackEvent.provisionalLine(firstEvents), "session 1 logs its provisional line")

        // Between sessions: New track here, then the start/finish placed on session 1's lap.
        val library = TrackLibrary(tracks, sessions)
        val made = library.createAt(gnss.centerLat, gnss.centerLon, "Moto Park")
        assertEquals(1, library.editorSessions(made).size, "the editor offers session 1's laps")
        val line = TrackLine(GeoPoint(gate.lat1, gate.lon1), GeoPoint(gate.lat2, gate.lon2))
        val track = library.save(made.copy(startFinish = line))

        // Session 2: timed on the new track and coached, the reference built from session 1.
        val log = LogLines().capture()
        val speaker = Speaker()
        val second = drive(speaker)
        assertNotNull(second.screen().coach?.text, "the coach line shows in session 2")
        second.stop()
        val events = SessionStore(sessions).read(second.id).events
        assertEquals(
            "saved ${track.id} rev ${track.revision} Moto Park",
            events.single { it.kind == TrackEvent.KIND }.text,
        )
        assertEquals(3, events.count { it.kind == EventKinds.LAP_LINE && "kind=Timed" in it.text })
        val logged = log.all()
        assertTrue(
            logged.any { it.startsWith("CoachService: coaching at Moto Park: a reference of 1 sessions") },
            logged.joinToString("\n"),
        )
        val cues = events.filter { it.kind == CoachEvents.CUE }
        assertTrue(cues.isNotEmpty(), "events: ${events.map { it.kind }}")
        assertEquals(cues.size, speaker.said.size)
        val reference = assertNotNull(IosCoachLibrary(sessions, references).reference(track, second.id, null))
        assertEquals(listOf(first.id), reference.sessions)
    }

    @Test
    fun aSessionWhoseProvisionalLineIsElsewhereIsNotInTheReference() {
        drive(speaker = null).stop()
        val library = TrackLibrary(tracks, sessions)
        // A track made 5 km away: session 1's line is outside its radius.
        val away = library.createAt(gnss.centerLat + 0.045, gnss.centerLon, "Elsewhere")
        val line = TrackLine(GeoPoint(gate.lat1 + 0.045, gate.lon1), GeoPoint(gate.lat2 + 0.045, gate.lon2))
        val track = library.save(away.copy(startFinish = line))
        val reference = IosCoachLibrary(sessions, references).reference(track, exclude = "", plan = null)
        assertTrue(reference == null || reference.sessions.isEmpty(), reference?.sessions.toString())
    }
}
