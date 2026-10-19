package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.TrackJson
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Silesia Ring replay built into the app for the Demo track (FBBD-237, FBBD-238): Arek's three
 * 27 Sep 2026 sessions, each imported as the app imports it, their timed laps joined by
 * [SessionReplay] in the order driven, next to the track they were timed at. The files in
 * app/src/main/assets/demo must be exactly what this makes from the corpus files; run with
 * -PwriteBuiltInReplay=true to write them again.
 */
class BuiltInReplayTest {
    @TempDir
    lateinit var tmp: File

    private val dir = File(checkNotNull(System.getProperty("builtInReplayDir")) { "builtInReplayDir not set" })
    private val track = RealCorpus.silesiaTrack()

    /** Each of the day's sessions replayed on its own, then all three joined in the order driven. */
    private fun made(): SessionReplay {
        val store = SessionStore(File(tmp, "sessions"))
        val replays = RealCorpus.SILESIA_DAY.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            assertNotNull(SessionReplay.of(store.read(id).sampleSets, track, 0L), name)
        }
        return SessionReplay.joined(replays, 0L)
    }

    @Test
    fun `the app's Silesia Ring replay is the corpus file's timed laps`() {
        val text = made().write()
        val trackText = TrackJson.encode(track)
        if (System.getProperty("writeBuiltInReplay") == "true") {
            dir.mkdirs()
            File(dir, ReplayFile).writeText(text)
            File(dir, TrackFile).writeText(trackText)
        }
        assertEquals(text, File(dir, ReplayFile).readText(), "run with -PwriteBuiltInReplay=true")
        assertEquals(track, TrackJson.decode(File(dir, TrackFile).readText()))
    }

    @Test
    fun `the built-in replay laps Silesia Ring at the day's lap times, sector by sector`() {
        val replay = SessionReplay.read(File(dir, ReplayFile).readText(), 0L)
        val fixes = (0L until 50L * 60 * replay.rateHz)
            .mapNotNull { n -> SampleSet(n * 1_000_000L / replay.rateHz, replay.fix(n)).toFix() }
        val laps = timeSession(track, fixes.asSequence()).laps.filter { it.kind == LapKind.Timed }
        assertTrue(laps.size >= 18, "${laps.size} laps in 50 min")
        assertTrue(laps.all { it.allSectors }, "every lap crosses every sector line")
        // Arek's 19 laps without a stop in them over the three sessions: 134 to 200 s.
        assertTrue(laps.all { it.timeMillis in 130_000L..205_000L }, "lap times ${laps.map { it.timeMillis }}")
    }

    private companion object {
        const val ReplayFile = "silesia-ring.replay"
        const val TrackFile = "silesia-ring.track.json"
    }
}
