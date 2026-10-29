@file:Suppress("MagicNumber") // Tolerances of the check.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.Lap
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.track.Track
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FBBD-335 on Arek's real sessions, with the track library hidden as at a new track (Moto Park
 * Kraków, 8 Nov 2026): each of his Jastrząb and Silesia sessions, recorded through live timing with
 * no saved track, gets a provisional start/finish and about the laps his saved track gives; his
 * road drives get none.
 */
class ProvisionalLineRealTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }

    private fun session(name: String): SessionData =
        store.read(RealCorpus.import(store, File(tmp, "staging"), name).id)

    /** The session's timed laps through live timing that knows [saved], and the provisional track if any. */
    private fun timed(data: SessionData, saved: List<Track>): Pair<List<Lap>, Track?> {
        val laps = mutableListOf<Lap>()
        val timing = SessionTiming({ saved }, CircuitList.Empty, onLap = { lap, _ -> laps += lap })
        data.sampleSets.forEach(timing::onSamples)
        return laps.filter { it.kind == LapKind.Timed } to timing.provisional
    }

    private fun check(files: List<String>, track: Track) {
        val rows = mutableListOf<String>()
        for (name in files) {
            val data = session(name)
            val (own, none) = timed(data, listOf(track))
            assertNull(none, "$name: a provisional line at a saved track with lines")
            val (laps, made) = timed(data, emptyList())
            assertNotNull(made, "$name: no provisional line")
            // The line sits elsewhere on the lap, so a lap may fall off or onto either end.
            assertTrue(abs(laps.size - own.size) <= 2, "$name: ${laps.size} laps, the saved line gives ${own.size}")
            // A lap from another point on the circuit sums the same corners, but a slow lap (traffic, a
            // pit stop) is split differently, so the median moves; the best lap is the same pace.
            val best = laps.minOf { it.timeMillis }
            val ownBest = own.minOf { it.timeMillis }
            rows += "$name: ${laps.size} laps (saved line ${own.size}), best $best ms (saved $ownBest ms)"
            assertTrue(abs(best - ownBest) <= 2_000, rows.last())
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
            val (laps, made) = timed(session(name), emptyList())
            assertNull(made, "$name: provisional line ${made?.startFinish}")
            assertTrue(laps.isEmpty(), name)
        }
    }
}
