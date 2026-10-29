@file:Suppress("MagicNumber") // Tolerances of the check.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.findSessionLoop
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FBBD-335 on Arek's real sessions, with the track library hidden as at a new track (Moto Park
 * Kraków, 8 Nov 2026): each of his Jastrząb and Silesia sessions, recorded through live timing with
 * no saved track, gets a provisional start/finish while there are laps left to drive, its live laps
 * are the laps that line gives after the session, the line sits where the whole session puts it,
 * and the lap count is about his saved track's; his road drives get none.
 */
class ProvisionalLineRealTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }

    private fun session(name: String): SessionData =
        store.read(RealCorpus.import(store, File(tmp, "staging"), name).id)

    /** Live timing's laps, its provisional track if any and the time of the fix that placed it. */
    private class Timed(val laps: List<Lap>, val made: Track?, val placedMicros: Long?)

    /** The session's timed laps through live timing that knows [saved], and the provisional track if any. */
    private fun timed(data: SessionData, saved: List<Track>): Timed {
        val laps = mutableListOf<Lap>()
        var placed: Long? = null
        val timing = SessionTiming({ saved }, CircuitList.Empty, onLap = { lap, _ -> laps += lap })
        for (set in data.sampleSets) {
            timing.onSamples(set)
            if (placed == null && timing.provisional != null) placed = set.toFix()?.tMicros
        }
        return Timed(laps.filter { it.kind == LapKind.Timed }, timing.provisional, placed)
    }

    /** Each lap of [data] through [track]'s line, timed after the session over all its fixes. */
    private fun timedAfter(data: SessionData, track: Track): List<Lap> {
        val timing = LiveTiming(track)
        data.sampleSets.forEach { set -> set.toFix()?.let(timing::update) }
        return timing.book.laps.filter { it.kind == LapKind.Timed }
    }

    private fun midpoint(line: TrackLine) =
        GeoPoint((line.left.lat + line.right.lat) / 2, (line.left.lon + line.right.lon) / 2)

    private fun check(files: List<String>, track: Track) {
        val rows = mutableListOf<String>()
        for (name in files) {
            val data = session(name)
            val known = timed(data, listOf(track))
            assertNull(known.made, "$name: a provisional line at a saved track with lines")
            val own = known.laps
            val live = timed(data, emptyList())
            val laps = live.laps
            val made = assertNotNull(live.made, "$name: no provisional line")
            val line = assertNotNull(made.startFinish)
            // Placed while there was still driving to time: before the session's last lap ended.
            val placed = assertNotNull(live.placedMicros)
            assertTrue(laps.any { it.endMicros > placed }, "$name: line placed after the last lap")
            // The laps live timing gave, the ones before the line included, are the laps of that line.
            val after = timedAfter(data, made)
            assertEquals(after.size, laps.size, "$name: live laps vs the same line after the session")
            after.zip(laps).forEach { (a, b) -> assertTrue(abs(a.timeMillis - b.timeMillis) <= 50, "$name: $a vs $b") }
            // The line the whole session gives sits where the live one did.
            val whole = assertNotNull(findSessionLoop(data.sampleSets.mapNotNull { it.toFix() }), name)
            val apart = distanceMeters(midpoint(line), midpoint(whole.startFinish))
            assertTrue(apart <= 30.0, "$name: provisional line $apart m from the whole session's")
            // The line sits elsewhere on the lap, so a lap may fall off or onto either end. The lap
            // times differ from the saved line's: a lap from another point sums the halves of two laps,
            // and Arek's laps alternate hot and cool (Silesia 13:53: best 141.6 s against 134.3 s).
            assertTrue(abs(laps.size - own.size) <= 2, "$name: ${laps.size} laps, the saved line gives ${own.size}")
            rows += "$name: ${laps.size} laps (saved line ${own.size}), line ${apart.toInt()} m off" +
                ", best ${laps.minOf { it.timeMillis }} ms (saved ${own.minOf { it.timeMillis }} ms)"
        }
        println(rows.joinToString("\n"))
    }

    @Test
    fun `every Jastrząb session gets a provisional line and its laps`() =
        check(RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack())

    @Test
    fun `every Silesia session gets a provisional line and its laps`() =
        check(RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack())

    @Test
    fun `road drives get no provisional line`() {
        for (name in RealCorpus.BETTERDRIVER) {
            val live = timed(session(name), emptyList())
            assertNull(live.made, "$name: provisional line ${live.made?.startFinish}")
            assertTrue(live.laps.isEmpty(), name)
        }
    }
}
