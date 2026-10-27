@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderConfig
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.Circuit
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A recording on the iPhone shows and logs the same laps as the Android recorder (FBBD-332). */
class LiveSessionTest {
    private val base = NSTemporaryDirectory() + "live-session-test-" + NSUUID().UUIDString
    private val sessions = "$base/sessions"
    private val tracks = "$base/tracks"
    private val gnss = FakeGnss()
    private val center = GeoPoint(gnss.centerLat, gnss.centerLon)
    private val gate = gnss.startFinish
    private val ring = Track(
        id = "ring", name = "Fake ring", center = center,
        startFinish = TrackLine(GeoPoint(gate.lat1, gate.lon1), GeoPoint(gate.lat2, gate.lon2)),
    )
    private val circuits = CircuitList(1, "test", listOf(Circuit("Q1", "Ring circuit", "PL", center)))
    private val info = SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL)

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(tracks, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun save(name: String, text: String) {
        NSString.create(string = text).writeToFile("$tracks/$name", true, NSUTF8StringEncoding, null)
    }

    /** Three and a half laps of 40 s at 25 Hz. */
    private fun drive(session: LiveSession) {
        for (n in 0L..(25 * 140)) session.submit(SampleSet(n * 40_000, gnss.fix(n)))
    }

    @Test
    fun aSavedTrackIsTimedAndItsLapsAreLogged() {
        save("ring.json", TrackJson.encode(ring))
        val session = LiveSession.start(sessions, tracks, circuits, info, receiverHasG = false)
        assertNull(session.screen().timing)
        drive(session)
        val screen = session.screen()
        assertEquals("Fake ring", screen.place)
        assertTrue(screen.placeSaved)
        val timing = assertNotNull(screen.timing)
        assertEquals(40_000L, timing.bestMillis)
        assertEquals(25, screen.shownRateHz)
        assertEquals(14, screen.live.sats)
        assertTrue(session.stop())
        assertFalse(session.isRecording)
        assertEquals("user", session.screen().stoppedReason)

        val events = SessionStore(sessions).read(session.id).events
        assertEquals("saved ring rev 1 Fake ring", events.single { it.kind == "track" }.text)
        assertEquals(3, events.count { it.kind == EventKinds.LAP_LINE && "kind=Timed" in it.text })
    }

    @Test
    fun withNoSavedTrackTheDirectoryNamesThePlace() {
        save("broken.json", "{ not a track")
        val session = LiveSession.start(sessions, tracks, circuits, info, receiverHasG = false)
        drive(session)
        val screen = session.screen()
        assertEquals("Ring circuit", screen.place)
        assertFalse(screen.placeSaved)
        assertNull(screen.timing)
        assertEquals(GnssState.Ok, screen.gnss)
        session.stop()
    }

    @Test
    fun lowStorageShowsOnTheTrackScreen() {
        // Any disk is below this warning level, and above the default stop level.
        val config = RecorderConfig(storageWarnBytes = Long.MAX_VALUE)
        val session = LiveSession.start(sessions, tracks, circuits, info, receiverHasG = false, config = config)
        session.submit(SampleSet(0, gnss.fix(0)))
        assertNotNull(session.screen().live.storageLowBytes)
        assertTrue(session.isRecording)
        session.stop()
    }

    @Test
    fun theStartButtonNamesThePlace() {
        save("ring.json", TrackJson.encode(ring))
        val saved = TrackFiles.read(tracks)
        assertEquals(listOf("ring"), saved.map { it.id })
        assertEquals("Fake ring", TrackFiles.placeAt(gnss.centerLat, gnss.centerLon, saved, circuits))
        assertEquals("Ring circuit", TrackFiles.placeAt(gnss.centerLat, gnss.centerLon, emptyList(), circuits))
        assertNull(TrackFiles.placeAt(0.0, 0.0, saved, circuits))
    }

    @Test
    fun anUnreadableCircuitListIsEmpty() {
        assertEquals(CircuitList.Empty, TrackFiles.circuits("{"))
        assertEquals(CircuitList.Empty, TrackFiles.circuits(null))
    }
}
