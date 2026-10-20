package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The replays built into the app for the Demo track: Arek's three Silesia Ring sessions of 27 Sep 2026
 * (FBBD-237, FBBD-238) and his six Autodrom Jastrząb sessions of 29 Aug 2026 (FBBD-254), each imported
 * as the app imports it, their timed laps joined by [SessionReplay] in the order driven, next to the
 * track they were timed at. The files in app/src/main/assets/demo must be exactly what this makes from
 * the corpus files; run with -PwriteBuiltInReplay=true to write them again.
 */
class BuiltInReplayTest {
    @TempDir
    lateinit var tmp: File

    private val dir = File(checkNotNull(System.getProperty("builtInReplayDir")) { "builtInReplayDir not set" })

    /** One built-in replay: its asset's name, the day's corpus files in the order driven, and their track. */
    private class BuiltIn(val asset: String, val files: List<String>, val track: Track)

    private val silesia = BuiltIn("silesia-ring", RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack())
    private val jastrzab = BuiltIn("jastrzab", RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack())

    /** Each of the day's sessions replayed on its own, then all of them joined in the order driven. */
    private fun made(builtIn: BuiltIn): SessionReplay {
        val store = SessionStore(File(tmp, "sessions"))
        val replays = builtIn.files.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(builtIn.track)).id
            assertNotNull(SessionReplay.of(store.read(id).sampleSets, builtIn.track, 0L), name)
        }
        return SessionReplay.joined(replays, 0L)
    }

    private fun checkAsset(builtIn: BuiltIn) {
        val text = made(builtIn).write()
        val trackText = TrackJson.encode(builtIn.track)
        val replayFile = File(dir, "${builtIn.asset}.replay")
        val trackFile = File(dir, "${builtIn.asset}.track.json")
        if (System.getProperty("writeBuiltInReplay") == "true") {
            dir.mkdirs()
            replayFile.writeText(text)
            trackFile.writeText(trackText)
        }
        assertEquals(text, replayFile.readText(), "run with -PwriteBuiltInReplay=true")
        assertEquals(builtIn.track, TrackJson.decode(trackFile.readText()))
    }

    /** The built-in replay of [builtIn] driven for [minutes], timed at its track. */
    private fun laps(builtIn: BuiltIn, minutes: Long) = run {
        val replay = SessionReplay.read(File(dir, "${builtIn.asset}.replay").readText(), 0L)
        val fixes = (0L until minutes * 60 * replay.rateHz)
            .mapNotNull { n -> SampleSet(n * 1_000_000L / replay.rateHz, replay.fix(n)).toFix() }
        timeSession(builtIn.track, fixes.asSequence()).laps.filter { it.kind == LapKind.Timed }
    }

    @Test
    fun `the app's Silesia Ring replay is the corpus file's timed laps`() = checkAsset(silesia)

    @Test
    fun `the app's Jastrzab replay is the corpus files' timed laps`() = checkAsset(jastrzab)

    @Test
    fun `the built-in replay laps Silesia Ring at the day's lap times, sector by sector`() {
        val laps = laps(silesia, 50L)
        assertTrue(laps.size >= 18, "${laps.size} laps in 50 min")
        assertTrue(laps.all { it.allSectors }, "every lap crosses every sector line")
        // Arek's 19 laps without a stop in them over the three sessions: 134 to 200 s.
        assertTrue(laps.all { it.timeMillis in 130_000L..205_000L }, "lap times ${laps.map { it.timeMillis }}")
    }

    @Test
    fun `the built-in replay laps Jastrzab at the day's lap times`() {
        val laps = laps(jastrzab, JastrzabMinutes)
        assertTrue(laps.size >= JastrzabLaps, "${laps.size} laps in $JastrzabMinutes min")
        // Arek's 25 laps without a stop in them over the six sessions: 110 to 151 s, a 3230 s loop.
        assertTrue(laps.all { it.timeMillis in JastrzabLapMillis }, "lap times ${laps.map { it.timeMillis }}")
    }

    private companion object {
        const val JastrzabMinutes = 54L
        const val JastrzabLaps = 24
        val JastrzabLapMillis = 105_000L..155_000L
    }
}
