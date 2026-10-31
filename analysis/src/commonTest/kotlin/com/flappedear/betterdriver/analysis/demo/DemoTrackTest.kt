package com.flappedear.betterdriver.analysis.demo

import com.flappedear.betterdriver.analysis.demoAsset
import com.flappedear.betterdriver.analysis.newTestDir
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.TrackStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Demo track's choice, Drives list and fixes, the same on Android and iOS (FBBD-373). */
class DemoTrackTest {
    private val files = newTestDir("demo-track")
    private val tracks = TrackStore(newTestDir("demo-tracks"))
    private val start = 1_790_000_000_000L

    private fun script(id: String?, session: (String) -> Nothing = { error("no session $it") }) =
        DemoTrack.script(id, tracks, ::demoAsset, session) { start }

    @Test
    fun drivesListTheCircuitTheBuiltInsThenSessionsNewestFirst() {
        val sessions = listOf(
            DemoSession("old", 1_000, "Silesia Ring", recording = false),
            DemoSession("now", 3_000, "", recording = true),
            DemoSession("new", 2_000, "", recording = false),
        )
        val choices = DemoTrack.choices(sessions) { "t$it" }
        assertEquals(
            listOf(
                DemoChoice(null, "Demo circuit (made up)"),
                DemoChoice(DemoTrack.SilesiaRing, "t${DemoTrack.SilesiaRingStartUtcMillis} · Silesia Ring"),
                DemoChoice(DemoTrack.Jastrzab, "t${DemoTrack.JastrzabStartUtcMillis} · Autodrom Jastrząb"),
                DemoChoice("new", "t2000"),
                DemoChoice("old", "t1000 · Silesia Ring"),
            ),
            choices,
        )
        assertEquals(DemoTrack.Circuit, DemoTrack.current(choices, "deleted"))
        assertEquals("old", DemoTrack.current(choices, "old").id)
    }

    @Test
    fun theChoiceIsKeptInItsFile() {
        assertNull(DemoTrack.chosen(files))
        DemoTrack.choose(files, DemoTrack.Jastrzab)
        assertEquals(DemoTrack.Jastrzab, DemoTrack.chosen(files))
        DemoTrack.choose(files, null)
        assertNull(DemoTrack.chosen(files))
    }

    @Test
    fun noChoiceDrivesTheDemoCircuitAndSavesItsTrack() {
        val circuit = assertIs<DemoCircuit>(script(null))
        assertEquals(start, circuit.startUtcMillis)
        assertEquals(DemoCircuit.TrackId, tracks.get(DemoCircuit.TrackId)?.id)
    }

    @Test
    fun eachBuiltInReplaysItsLapsAtItsSavedTrack() {
        for (drive in DemoTrack.builtIns) {
            val replay = assertIs<SessionReplay>(script(drive.id))
            val track = TrackJson.decode(demoAsset("${drive.asset}.track.json"))
            val saved = checkNotNull(tracks.get(track.id))
            assertTrue(replay.timesLaps(saved), drive.id)
            // Ten minutes of it at 5 Hz, as the recorder gets it: timed laps at the saved track.
            val rate = 5
            val fixes = (0L until 10L * 60 * rate).asSequence().mapNotNull { n ->
                SampleSet(n * 1_000_000L / rate, replay.fix(n * replay.rateHz / rate)).toFix()
            }
            val laps = timeSession(saved, fixes).laps
            assertTrue(laps.count { it.kind == LapKind.Timed } >= 2, "${drive.id}: ${laps.map { it.kind }}")
        }
    }

    @Test
    fun aBuiltInTrackWhoseLineNoLongerTimesTheLapsGetsItsLineBack() {
        val track = TrackJson.decode(demoAsset("jastrzab.track.json"))
        val far = GeoPoint(track.center.lat + 0.1, track.center.lon)
        tracks.save(track.copy(name = "Arek's name", startFinish = TrackLine.across(far, 0.0)))
        script(DemoTrack.Jastrzab)
        val mended = checkNotNull(tracks.get(track.id))
        assertEquals(track.startFinish, mended.startFinish)
        assertEquals("Arek's name", mended.name)
    }

    @Test
    fun aSessionThatCannotBeReadDrivesTheDemoCircuit() {
        assertIs<DemoCircuit>(script("gone"))
        assertTrue(GnssChannels.ALL.isNotEmpty())
    }
}
