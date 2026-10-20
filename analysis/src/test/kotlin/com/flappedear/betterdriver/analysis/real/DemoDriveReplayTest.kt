@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.quality.QualityMetric
import com.flappedear.betterdriver.analysis.quality.Verdict
import com.flappedear.betterdriver.analysis.quality.coachReplayChecks
import com.flappedear.betterdriver.analysis.quality.cueOverlap
import com.flappedear.betterdriver.analysis.quality.replayCues
import com.flappedear.betterdriver.analysis.quality.replayLaps
import com.flappedear.betterdriver.analysis.quality.replayQuiet
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachFix
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachSession
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.DefaultLatencyMicros
import com.flappedear.betterdriver.coach.PhraseBook
import com.flappedear.betterdriver.coach.Verbosity
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
import kotlin.test.assertTrue

/**
 * Arek's Demo track drive on the computer: the Silesia Ring laps built into the app (his 27 Sep 2026
 * sessions, FBBD-237) driven once round, all 19 laps, with the coach at Full as he sets it, in Polish
 * and in English. Live timing and the coach run as the recorder and coach processes run them, fed every
 * 25 Hz fix as it arrives, and their events are checked as the drive-log reports check his logs
 * (FBBD-244, FBBD-242, FBBD-243, FBBD-245). The emulator replay (replay-drive.sh) checks the same in
 * the whole app, with its voice; this one runs every lap in seconds.
 *
 * The voice is played as a phone plays it (FBBD-253): each phrase takes longer than the coach's own
 * estimate, a new phrase cuts off one still playing, and now and then the coach process stalls and
 * gets a second of fixes at once, so its clock runs ahead of the sound. No cue may overlap another.
 *
 * Each run's laps and cues are written to build/reports/replay-drive, for the pull request's checks.
 */
class DemoDriveReplayTest {
    private val dir = File(System.getProperty("builtInReplayDir") ?: "../app/src/main/assets/demo")
    private val track = TrackJson.decode(File(dir, "silesia-ring.track.json").readText())

    /** The car's speed at each fix of the drive, km/h, by session time. */
    private val speeds = mutableListOf<Pair<Long, Double>>()

    /** The events one drive round the loop logs, as the recorder stores them. */
    private fun drive(language: CoachLanguage): List<RecorderEvent> {
        speeds.clear()
        val replay = SessionReplay.read(File(dir, "silesia-ring.replay").readText(), 0L)
        val coach = CoachSession(CoachSettings(Verbosity.Full, language = language), track, null)
        val timing = LiveTiming(track)
        val events = mutableListOf<RecorderEvent>()
        val voice = SimVoice(PhraseBook.of(language))
        for (n in 0 until LoopSeconds * replay.rateHz) {
            val t = n * 1_000_000L / replay.rateHz
            val values = replay.fix(n)
            val fix = SampleSet(t, values).toFix() ?: continue
            val before = timing.book.count
            val shown = timing.state.predictedMillis
            timing.update(fix)
            timing.book.laps.drop(before).forEach {
                events += RecorderEvent(t, EventKinds.LAP_LINE, lapLineText(it, shown))
            }
            val kmh = values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value
            kmh?.let { speeds += t to it }
            val now = handledAt(t)
            voice.at(now)
            val out = coach.onFix(CoachFix(t, fix.lat, fix.lon, kmh), now, voiceFree = !voice.speaking) {
                voice.say(it, now)
            }
            out.events.forEach { events += RecorderEvent(it.tMicros, it.kind, it.text) }
            out.events.firstOrNull { it.kind == CoachEvents.CUE }
                ?.let { voice.numbered(CoachEvents.parseCue(it.tMicros, it.text)!!.seq) }
        }
        voice.at(Long.MAX_VALUE)
        return (events + voice.events).sortedBy { it.tMicros }
    }

    /**
     * When the coach process handles the fix taken at [t]: at once, except in a stall of [StallMicros]
     * every [StallEveryMicros], whose fixes all arrive at its end, as a busy phone delivers them.
     */
    private fun handledAt(t: Long): Long {
        val inCycle = t % StallEveryMicros
        val stallStart = StallEveryMicros - StallMicros
        return if (inCycle >= stallStart) t - inCycle + StallEveryMicros else t
    }

    /**
     * The coach's voice as the phone plays it: the sound starts [DefaultLatencyMicros] after it is asked for and
     * lasts [SlowerThanEstimate] times the coach's estimate; a new phrase cuts off one still playing, as
     * the app's text-to-speech does. Logs each phrase's start and end as CoachService does.
     */
    private class SimVoice(private val phrases: PhraseBook) {
        val events = mutableListOf<RecorderEvent>()
        private var seq: Int? = null
        private var asked = 0L
        private var start = 0L
        private var end = Long.MIN_VALUE
        private var said = true

        val speaking get() = !said

        /** The clock is at [now]: a phrase that has ended by then is logged as done. */
        fun at(now: Long) {
            if (!said && end <= now) finish(end, "done")
        }

        fun say(text: String, now: Long): Boolean {
            if (!said) finish(now, "stopped")
            asked = now
            start = now + DefaultLatencyMicros
            end = start + (phrases.durationMicros(text) * SlowerThanEstimate).toLong()
            seq = null
            said = false
            return true
        }

        /** The phrase just asked for is cue [n]: its sound start is logged against it. */
        fun numbered(n: Int) {
            seq = n
            events += RecorderEvent(start, EventKinds.COACH_VOICE, CoachEvents.voiceText(n, "live", asked, "speaker"))
        }

        private fun finish(t: Long, how: String) {
            said = true
            seq?.let { events += RecorderEvent(t, EventKinds.COACH_VOICE_END, CoachEvents.voiceEndText(it, how)) }
        }
    }

    private fun check(language: CoachLanguage) {
        val events = drive(language)
        val metrics = coachReplayChecks(events, language.tag) + cueOverlap(events)
        report(language, events, metrics)
        // The built-in loop drops the stops in the pit lane (FBBD-237), so no lap ends there: laps 6 and 13
        // leave it at 16 and 49 km/h, which DemoReplayCoachTest checks are coached.
        for (m in metrics.filter { it.id != "coach.pit_out" }) {
            assertEquals(Verdict.PASS, m.verdict, "${m.label}: ${m.how}")
        }
        // FBBD-47: the coach starts a corner cue a second sooner than before (lead 2.5 s, was 1.5 s), so the
        // speech engine's delay is absorbed and the cue still ends well before the braking zone.
        // One cue per straight (FBBD-27), praise before the straight's cue aside (UI-94 A), as the drive
        // check coach.per_straight judges it: a cue that starts early must not let a praise follow it.
        val extra = sameStraight(events, language)
        assertTrue(extra.isEmpty(), "cues on one straight: $extra")
        val spare = spareSeconds(events, language).sorted()
        println("corner cues ${language.tag}: ${spare.size}, spare before the braking zone " +
            "min ${"%.2f".format(spare.first())} s, median ${"%.2f".format(spare[spare.size / 2])} s")
        assertTrue(spare.first() >= MinSpareSeconds, "a cue ends ${spare.first()} s before its braking zone")
        assertTrue(spare[spare.size / 2] >= MinMedianSpareSeconds, "median spare ${spare[spare.size / 2]} s")
    }

    /**
     * Pairs of cues said one after the other with no slowing of [BrakingDropKmh] between their sound
     * starts (asked for at the trigger, heard after [DefaultLatencyMicros]), praise before a corner cue aside.
     */
    private fun sameStraight(events: List<RecorderEvent>, language: CoachLanguage): List<String> {
        val cues = events.filter { it.kind == "coach_cue" }
        return cues.zipWithNext().filter { (a, b) ->
            val praiseThenCue = " kind=Praise" in a.text && " kind=Corner" in b.text
            val from = a.tMicros + DefaultLatencyMicros
            val to = b.tMicros + DefaultLatencyMicros
            var top = Double.NEGATIVE_INFINITY
            val braked = speeds.any { (t, v) ->
                t in from..to && run {
                    top = maxOf(top, v)
                    top - v >= BrakingDropKmh
                }
            }
            !praiseThenCue && !braked
        }.map { (a, b) -> "${language.tag}: ${a.text.substringAfter(" text=")} / ${b.text.substringAfter(" text=")}" }
    }

    /**
     * For each corner cue: the time from the end of its sound (asked for at its trigger, said after
     * [DefaultLatencyMicros] for as long as its phrase lasts) to the car reaching the braking zone the
     * coach logged ahead of it (`zone_m`), by the replay's own speeds.
     */
    private fun spareSeconds(events: List<RecorderEvent>, language: CoachLanguage): List<Double> {
        val book = PhraseBook.of(language)
        return events.filter { it.kind == "coach_cue" && " kind=Corner" in it.text }.mapNotNull { e ->
            val zone = ZoneMeters.find(e.text)?.groupValues?.get(1)?.toDouble() ?: return@mapNotNull null
            val text = e.text.substringAfter(" text=")
            val ends = e.tMicros + DefaultLatencyMicros + book.durationMicros(text)
            var driven = 0.0
            var k = speeds.indexOfFirst { it.first >= e.tMicros }.takeIf { it >= 0 } ?: return@mapNotNull null
            while (k + 1 < speeds.size && driven < zone) {
                driven += speeds[k].second / 3.6 * (speeds[k + 1].first - speeds[k].first) / 1e6
                k++
            }
            (speeds[k].first - ends) / 1e6
        }
    }

    @Test
    fun `the Polish coach on every lap of the built-in Silesia Ring drive`() = check(CoachLanguage.Polish)

    @Test
    fun `the English coach on every lap of the built-in Silesia Ring drive`() = check(CoachLanguage.English)

    /** Laps, cues and checks as a Markdown page, as the drive-log reports give them. */
    private fun report(language: CoachLanguage, events: List<RecorderEvent>, metrics: List<QualityMetric>) {
        val cues = replayCues(events)
        val quiet = replayQuiet(events)
        val text = buildString {
            appendLine("## Built-in Silesia Ring replay on the JVM, coach ${language.name}, Full")
            appendLine()
            appendLine("| Check | Value | Verdict | How |")
            appendLine("| --- | --- | --- | --- |")
            metrics.forEach {
                appendLine("| ${it.label} | ${it.value ?: "-"} ${it.unit} | ${it.verdict} | ${it.how} |")
            }
            appendLine()
            appendLine("| Lap | Kind | Time | Coach quiet | Cues |")
            appendLine("| --- | --- | --- | --- | --- |")
            for (lap in replayLaps(events)) {
                val said = cues.filter { it.lap == lap.number }.joinToString("; ") { it.text }
                val still = quiet.filter { it.lap == lap.number }.joinToString { it.reason }
                val time = "%.1f".format(lap.timeMillis / 1000.0)
                appendLine("| ${lap.number} | ${lap.kind} | $time s | $still | $said |")
            }
        }
        File("build/reports/replay-drive").apply { mkdirs() }.resolve("jvm-${language.tag}.md").writeText(text)
        println(text)
    }

    private companion object {
        /** The built-in replay's loop, 2828 s, once round. */
        const val LoopSeconds = 2_830L

        /** The drive check's slowing between two cues on different straights (coach.per_straight). */
        const val BrakingDropKmh = 15.0

        val ZoneMeters = Regex(" zone_m=([0-9.]+)")

        /**
         * Every corner cue ends at least this long before its braking zone. On the shortest straights the cue
         * goes as soon as the car is on them, so this did not change with the lead (0.26 s in Polish).
         */
        const val MinSpareSeconds = 0.2

        /** Half the corner cues end at least this long before it: 2.50 s Polish, 2.54 s English (was 1.54, 1.66). */
        const val MinMedianSpareSeconds = 2.3

        /** How much longer a phrase takes than the coach's estimate: Polish numbers run long. */
        const val SlowerThanEstimate = 1.5

        /** The coach process stalls this long every [StallEveryMicros]. */
        const val StallMicros = 1_500_000L
        const val StallEveryMicros = 47_000_000L
    }
}
