@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackLine
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live coach on the iPhone says and logs its cues as the Android coach does (FBBD-333). */
class LiveCoachTest {
    private val base = NSTemporaryDirectory() + "live-coach-test-" + NSUUID().UUIDString
    private val sessions = "$base/sessions"
    private val tracks = "$base/tracks"
    private val references = "$base/coach"
    private val gnss = FakeGnss()
    private val gate = gnss.startFinish
    private val ring = Track(
        id = "ring", name = "Fake ring", center = GeoPoint(gnss.centerLat, gnss.centerLon),
        startFinish = TrackLine(GeoPoint(gate.lat1, gate.lon1), GeoPoint(gate.lat2, gate.lon2)),
    )

    /** Says everything at once, as a voice with nothing playing. */
    private class Speaker : CoachSpeaker {
        val said = mutableListOf<String>()
        override val speaking = false

        override fun say(text: String, speech: CoachSpeech): Boolean {
            said += text
            speech.started("speaker", "builtInSpeaker")
            speech.ended("done")
            return true
        }
    }

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(tracks, true, null, null)
        NSString.create(string = TrackJson.encode(ring))
            .writeToFile("$tracks/ring.json", true, NSUTF8StringEncoding, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    /** Three and a half laps of 40 s at 25 Hz, coached with [settings]. */
    private fun coachedSession(settings: CoachSettings, speaker: Speaker): LiveSession {
        val info = SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL)
        val session = LiveSession.start(sessions, tracks, CircuitList.Empty, info, receiverHasG = false)
        session.startCoach(sessions, references, settings, audioDelayFromPhone = true, speaker = speaker)
        for (n in 0L..(25 * 140)) session.submit(SampleSet(n * 40_000, gnss.fix(n)))
        session.awaitCoach()
        session.awaitCoach()
        return session
    }

    @Test
    fun lapTimesAreSaidAndLoggedWithTheirSound() {
        val speaker = Speaker()
        val session = coachedSession(coachSettings(polish = false, audioDelayMillis = 120), speaker)
        assertNotNull(session.screen().coach?.text, "the coach line shows once the coach runs")
        session.stop()

        val events = SessionStore(sessions).read(session.id).events
        val cues = events.filter { it.kind == CoachEvents.CUE }
        assertTrue(cues.isNotEmpty(), "events: ${events.map { it.kind }}")
        assertTrue(cues.all { "kind=LapTime" in it.text }, cues.joinToString { it.text })
        assertEquals(cues.size, speaker.said.size)
        val voice = events.filter { it.kind == CoachEvents.VOICE }
        assertEquals(cues.size, voice.size)
        assertTrue("seq=1 path=live" in voice.first().text, voice.first().text)
        assertTrue("out_delay_ms=120 out_delay_src=measured" in voice.first().text, voice.first().text)
        assertEquals(cues.size, events.count { it.kind == CoachEvents.VOICE_END && "end=done" in it.text })
    }

    @Test
    fun theNextSessionGetsAReferenceFromTheFirst() {
        coachedSession(coachSettings(polish = true, audioDelayMillis = 0), Speaker()).stop()
        val second = coachedSession(coachSettings(polish = true, audioDelayMillis = 0), Speaker())
        second.stop()
        val reference = IosCoachLibrary(sessions, references).reference(ring, exclude = second.id)
        assertNotNull(reference, "built from the first session's laps")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$references/ring.coach.json"))
    }

    @Test
    fun withTheCoachOffThereIsNoCoachLine() {
        val session = coachedSession(CoachSettings(verbosity = Verbosity.Off), Speaker())
        assertNull(session.screen().coach)
        session.stop()
        assertTrue(SessionStore(sessions).read(session.id).events.none { it.kind == CoachEvents.CUE })
    }
}
