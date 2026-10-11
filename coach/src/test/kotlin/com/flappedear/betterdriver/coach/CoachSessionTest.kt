@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
            val out = session.onFix(f, f.tMicros)
            events += out.events
            said += out.say
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
    fun `a cue that comes too late is logged, not said`() {
        val session = CoachSession(CoachSettings(Verbosity.LapTimes), track, null)
        val events = mutableListOf<CoachEvent>()
        val said = mutableListOf<String>()
        for (f in sim.drive(List(2) { SimCircuit.Pace() }).fixes) {
            val out = session.onFix(f, f.tMicros + 1_000_000)
            events += out.events
            said += out.say
        }
        assertTrue(said.isEmpty())
        assertTrue(events.isNotEmpty() && events.all { it.kind == CoachEvents.PROBLEM && it.text.startsWith("late") })
    }

    @Test
    fun `no track or coach off says nothing`() {
        val off = CoachSession(CoachSettings(Verbosity.Off), track, null)
        val none = CoachSession(CoachSettings(), null, null)
        for (f in sim.drive(List(2) { SimCircuit.Pace() }).fixes) {
            assertTrue(off.onFix(f, f.tMicros).say.isEmpty())
            assertTrue(none.onFix(f, f.tMicros).say.isEmpty())
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
