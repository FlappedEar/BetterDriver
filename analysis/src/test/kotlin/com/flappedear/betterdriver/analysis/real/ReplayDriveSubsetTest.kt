package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.quality.Verdict
import com.flappedear.betterdriver.analysis.quality.coachReplayChecks
import com.flappedear.betterdriver.coach.CoachFix
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachSession
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.toCoachFix
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.live.LiveTiming
import com.flappedear.betterdriver.timing.live.lapLineText
import com.flappedear.betterdriver.timing.live.toFix
import com.flappedear.betterdriver.timing.track.TrackJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The emulator's Replay drive (replay-drive.sh, ReplayDriveTest) as CI runs it, on the computer: 10
 * minutes of the built-in Silesia Ring laps, with the coach knowing the track from an earlier outing
 * (one drive round the loop, as Arek's 27 Sep sessions on his phone) and learning [LearningLaps] lap.
 * It must still coach two laps with cues said with their numbers, or the emulator's coach checks would
 * pass only by not being measured. DemoDriveReplayTest drives every lap.
 */
class ReplayDriveSubsetTest {
    private val dir = File(System.getProperty("builtInReplayDir") ?: "../app/src/main/assets/demo")
    private val track = TrackJson.decode(File(dir, "silesia-ring.track.json").readText())
    private val replay = SessionReplay.read(File(dir, "silesia-ring.replay").readText(), 0L)

    private fun sampleSet(n: Long) = SampleSet(n * MicrosPerSecond / replay.rateHz, replay.fix(n))

    @Test
    fun `the emulator's 10-minute drive coaches two laps with numbers`() {
        val fixes = (0 until LoopSeconds * replay.rateHz).asSequence().mapNotNull { sampleSet(it).toCoachFix() }
        val earlier = checkNotNull(buildReference(track, listOf(SessionLaps("earlier", tracedLaps(track, fixes)))))
        val language = CoachLanguage.Polish
        val settings = CoachSettings(Verbosity.Full, language = language, learningLaps = LearningLaps)
        val coach = CoachSession(settings, track, earlier)
        val timing = LiveTiming(track)
        val events = mutableListOf<RecorderEvent>()
        for (n in 0 until DriveSeconds * replay.rateHz) {
            val set = sampleSet(n)
            val fix = set.toFix() ?: continue
            val before = timing.book.count
            val shown = timing.state.predictedMillis
            timing.update(fix)
            timing.book.laps.drop(before).forEach {
                events += RecorderEvent(set.tMicros, EventKinds.LAP_LINE, lapLineText(it, shown))
            }
            val kmh = set.values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value
            coach.onFix(CoachFix(set.tMicros, fix.lat, fix.lon, kmh), set.tMicros) { true }.events
                .forEach { events += RecorderEvent(it.tMicros, it.kind, it.text) }
        }
        val metrics = coachReplayChecks(events, language.tag).associateBy { it.id }
        metrics.values.forEach { println("${it.id}: ${it.verdict}, ${it.how}") }
        for (id in listOf("coach.silent_laps", "coach.absolute", "coach.language", "coach.confidence")) {
            val m = metrics.getValue(id)
            assertEquals(Verdict.PASS, m.verdict, "${m.label}: ${m.how}")
        }
    }

    private companion object {
        const val MicrosPerSecond = 1_000_000L

        /** The built-in replay's loop, 2828 s, once round: the earlier outing. */
        const val LoopSeconds = 2_830L

        /**
         * replay-drive.sh's REPLAY_MINUTES. The emulator's replay runs a little longer, from the recording's
         * start until it is stopped. Lap 4, the second coached lap, ends at 582 s.
         */
        const val DriveSeconds = 600L

        /** The learning laps ReplayDriveTest sets: with the earlier outing known, lap 3 is coached. */
        const val LearningLaps = 1
    }
}
