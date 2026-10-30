@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.timing.track.Track
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The coach run both apps use (FBBD-357): what it logs and reports, from the session's start to its cues. */
class CoachRunTest {
    @TempDir
    lateinit var tmp: File

    private val sim = SimCircuit()
    private val track = sim.track()
    private val early2 = SimCircuit.Pace(earlierM = listOf(0.0, 15.0, 0.0, 0.0))
    private val logged = mutableListOf<String>()
    private val events = mutableListOf<Pair<String, String>>()
    private val said = mutableListOf<String>()
    private var clock = 0L

    /** The voice's callbacks, run after the fix that asked for the cue, as the platforms post them. */
    private val posted = ArrayDeque<() -> Unit>()

    init {
        DebugLog.echo = { _, tag, message, _ -> logged += "$tag: $message" }
    }

    @AfterEach
    fun stopEcho() {
        DebugLog.echo = null
    }

    private val reference: CoachReference by lazy {
        val laps = tracedLaps(track, sim.drive(List(3) { SimCircuit.Pace() }).fixes.asSequence())
        assertNotNull(buildReference(track, listOf(SessionLaps("earlier", laps))))
    }

    /** Says each phrase at once, to [device]. */
    private inner class Voice : CoachVoiceOut {
        var device = "speaker"
        override val speaking = false

        override fun say(text: String, sound: CueSound): Boolean {
            said += text
            sound.started(CueSoundStart(clock, "live", if (device == "speaker") "speaker" else "headset", device))
            sound.ended(clock, "done")
            return true
        }
    }

    private fun run(settings: CoachSettings, voice: CoachVoiceOut = Voice()) = CoachRun(
        "s1", settings, CoachFolders.under(File(tmp, "coach").path, File(tmp, "sessions").path),
        object : CoachReferenceSource {
            override fun reference(track: Track, exclude: String, plan: PlannedTargets?) = reference

            override fun drivenSameDay(reference: CoachReference, nowUtcMillis: Long) = false
        },
        voice, { clock }, { 0L }, { posted.addLast(it) }, { _, kind, text -> events += kind to text },
    )

    private fun CoachRun.drive(fixes: List<CoachFix>) {
        for (f in fixes) {
            clock = f.tMicros
            fix(f)
            while (posted.isNotEmpty()) posted.removeFirst()()
        }
    }

    @Test
    fun `cues are timed for the delay measured at the start and logged with Android's lines`() {
        CoachPicksStore(File(tmp, "coach/picks").path).save(
            track.id, listOf(CornerPick("Corner 2", listOf(0.0, 0.0), listOf(0.0, 0.0001))),
        )
        val voice = Voice()
        val run = run(CoachSettings(Verbosity.Full, audioDelayMillis = 300), voice)
        run.delays.measured("live", "speaker", 120)
        run.begin("live", "speaker")
        assertTrue(
            logged.any { it.startsWith("CoachService: coaching session s1:") && it.endsWith("measured on speaker") },
            logged.joinToString("\n"),
        )
        run.start(track)
        assertTrue("CoachService: picked corners: Corner 2" in logged, logged.joinToString("\n"))
        assertTrue(
            logged.any { it.startsWith("CoachService: coaching at ${track.name}: a reference of 1 sessions") },
            logged.joinToString("\n"),
        )
        assertTrue(logged.any { it.endsWith("delay 120 ms (measured)") }, logged.joinToString("\n"))
        val fixes = sim.drive(List(5) { early2 }).fixes
        val half = fixes.size / 2
        run.drive(fixes.take(half))
        // A headset with no reported delay: the one set is used from now on (FBBD-354).
        voice.device = "bt_a2dp"
        run.delays.forget("bt_a2dp")
        run.outputChanged("live", "bt_a2dp")
        assertTrue("CoachService: cues now timed for an audio delay of 300 ms (set) on bt_a2dp" in logged)
        run.drive(fixes.drop(half))
        val cues = events.filter { it.first == CoachEvents.CUE }
        assertTrue(cues.size >= 2, events.joinToString())
        assertEquals(cues.size, said.size)
        val sounds = events.filter { it.first == CoachEvents.VOICE }.map { it.second }
        assertEquals(cues.size, sounds.size)
        assertTrue("out_delay_ms=120 out_delay_src=measured timed_src=measured" in sounds.first(), sounds.first())
        assertTrue("out_delay_ms=300 out_delay_src=set timed_src=set" in sounds.last(), sounds.last())
        assertEquals(cues.size, events.count { it.first == CoachEvents.VOICE_END })
    }

    @Test
    fun `away from a saved track nothing is cued, and a failure is a coach problem`() {
        val run = run(CoachSettings(Verbosity.Full))
        run.begin("live", "speaker")
        run.start(null)
        assertTrue("CoachService: not at a saved track: no cues this session" in logged, logged.joinToString("\n"))
        run.drive(sim.drive(List(2) { early2 }).fixes)
        assertTrue(said.isEmpty())
        run.failed(IllegalStateException("broken"))
        assertEquals(CoachEvents.PROBLEM to "error java.lang.IllegalStateException: broken", events.last())
        assertTrue(logged.any { it == "CoachService: coach failed" }, logged.joinToString("\n"))
    }
}
