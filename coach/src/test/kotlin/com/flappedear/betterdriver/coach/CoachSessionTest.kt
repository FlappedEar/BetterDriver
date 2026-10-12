@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.track.TrackKind
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoachSessionTest {
    @TempDir
    lateinit var tmp: File

    private val sim = SimCircuit()
    private val track = sim.track()
    private val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))

    private fun reference(): CoachReference {
        val laps = tracedLaps(track, sim.drive(List(3) { SimCircuit.Pace() }).fixes.asSequence())
        return assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    @Test
    fun `cues and results are logged as events that read back`() {
        val session = CoachSession(CoachSettings(Verbosity.Full), track, reference())
        assertEquals(CoachStatus(null, "Coach learning · 0 of 3 laps"), session.status)
        val events = mutableListOf<CoachEvent>()
        val said = mutableListOf<String>()
        for (f in sim.drive(List(5) { early2 }).fixes) {
            val out = session.onFix(f, f.tMicros) { said.add(it) }
            events += out.events
        }
        val cueEvent = events.single { it.kind == CoachEvents.CUE && "kind=Corner" in it.text }
        val cue = assertNotNull(CoachEvents.parseCue(cueEvent.tMicros, cueEvent.text))
        assertEquals(2, cue.corner)
        assertTrue(cue.advice is Advice.BrakeLater)
        assertTrue(cue.text in said)
        assertEquals(cue.before!!.corner, 2)
        val result = assertNotNull(CoachEvents.parseResult(events.single { it.kind == CoachEvents.RESULT }.text))
        assertEquals(cue.seq, result.seq)
        assertEquals(2, result.after.corner)
        // The last cue said was a lap time, shown without a corner.
        assertEquals(null, session.status.corner)
    }

    @Test
    fun `with no learning laps and no earlier session it learns one lap, not 0 of 0`() {
        val first = CoachSession(CoachSettings(Verbosity.Full, learningLaps = 0), track, null)
        assertEquals(CoachStatus(null, "Coach learning · 0 of 1 laps"), first.status)
        val known = CoachSession(CoachSettings(Verbosity.Full, learningLaps = 0), track, reference())
        assertEquals(CoachStatus(null, "Coach on"), known.status)
        for (f in sim.drive(List(2) { SimCircuit.Pace() }).fixes) first.onFix(f, f.tMicros) { true }
        assertFalse("learning" in first.status.text.orEmpty())
    }

    @Test
    fun `the coach stays quiet at a point-to-point track`() {
        val p2p = track.copy(kind = TrackKind.PointToPoint, finish = track.startFinish)
        assertFalse(coaches(p2p))
        assertTrue(coaches(track))
        val session = CoachSession(CoachSettings(Verbosity.Full), p2p, null)
        assertEquals(CoachStatus.None, session.status)
        val said = mutableListOf<String>()
        for (f in sim.drive(List(3) { SimCircuit.Pace() }).fixes) {
            assertTrue(session.onFix(f, f.tMicros) { said.add(it) }.events.isEmpty())
        }
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a cue that comes too late is logged, not said`() {
        val session = CoachSession(CoachSettings(Verbosity.LapTimes), track, null)
        val events = mutableListOf<CoachEvent>()
        val said = mutableListOf<String>()
        for (f in sim.drive(List(2) { SimCircuit.Pace() }).fixes) {
            val out = session.onFix(f, f.tMicros + 1_000_000) { said.add(it) }
            events += out.events
        }
        assertTrue(said.isEmpty())
        assertTrue(events.isNotEmpty() && events.all { it.kind == CoachEvents.PROBLEM && it.text.startsWith("late") })
    }

    @Test
    fun `a cue the voice refuses is logged as a problem, gets no result and is tried again next lap`() {
        val session = CoachSession(CoachSettings(Verbosity.Full), track, reference())
        val events = mutableListOf<CoachEvent>()
        var refused = 0
        val say = { text: String -> !(text.startsWith("Turn 2") && refused++ == 0) }
        for (f in sim.drive(List(6) { early2 }).fixes) events += session.onFix(f, f.tMicros, say).events
        assertEquals(1, refused - 1, "the first turn 2 cue was refused, the second said")
        val problem = events.single { it.kind == CoachEvents.PROBLEM }
        assertTrue(problem.text.startsWith("no voice seq="), problem.text)
        val unsaidSeq = problem.text.removePrefix("no voice ").let { CoachEvents.parseCue(problem.tMicros, it) }!!.seq
        val cues = events.filter { it.kind == CoachEvents.CUE }.mapNotNull { CoachEvents.parseCue(it.tMicros, it.text) }
        assertTrue(cues.none { it.seq == unsaidSeq })
        val corner2 = cues.single { it.corner == 2 }
        val results = events.filter { it.kind == CoachEvents.RESULT }.mapNotNull { CoachEvents.parseResult(it.text) }
        assertEquals(listOf(corner2.seq), results.map { it.seq })
        // Retried on the very next lap, not rested for one.
        val refusedLap = CoachEvents.parseCue(problem.tMicros, problem.text.removePrefix("no voice "))!!.lap
        assertEquals(refusedLap + 1, corner2.lap)
    }

    @Test
    fun `no track or coach off says nothing`() {
        val off = CoachSession(CoachSettings(Verbosity.Off), track, null)
        val none = CoachSession(CoachSettings(), null, null)
        for (f in sim.drive(List(2) { SimCircuit.Pace() }).fixes) {
            off.onFix(f, f.tMicros) { error("said $it") }
            none.onFix(f, f.tMicros) { error("said $it") }
        }
        assertEquals(CoachStatus.None, none.status)
    }

    @Test
    fun `settings survive a round trip and a damaged file gives defaults`() {
        val store = CoachSettingsStore(File(tmp, "coach/settings.properties"))
        assertEquals(CoachSettings(), store.read())
        val s = CoachSettings(Verbosity.Full, audioDelayMillis = 700, learningLaps = 2)
        store.write(s)
        assertEquals(s, store.read())
        File(tmp, "coach/settings.properties").writeText("verbosity=Loud\naudioDelayMillis=99999\n")
        assertEquals(CoachSettings(audioDelayMillis = CoachSettings.MaxAudioDelayMillis), store.read())
    }
}
