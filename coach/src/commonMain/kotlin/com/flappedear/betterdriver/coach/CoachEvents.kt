package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.timing.steering.SteerAdvice
import com.flappedear.betterdriver.timing.steering.SteeringPass

/**
 * How cues and their results are written into the session as events (FBBD-98), so the cue review
 * (FBBD-28, FBBD-99) reads exactly what was said and what happened next. Text is `key=value` pairs
 * separated by spaces; the spoken text comes last, after `text=`, and may hold spaces.
 */
@Suppress("TooManyFunctions") // One writer and one reader per kind of event.
object CoachEvents {
    /** A cue was spoken; its event time is when the coach asked for it. */
    const val CUE = "coach_cue"

    /** The pass right after a corner cue. */
    const val RESULT = "coach_result"

    /** The coach could not speak: no voice, or a cue came too late to say. Text is the reason. */
    const val PROBLEM = "coach_problem"

    /**
     * A cue's sound started (FBBD-183); text from [voiceText]. Defined with the recorder's event kinds
     * so the session validation report (`:analysis`) reads it too.
     */
    const val VOICE = EventKinds.COACH_VOICE

    /** A cue's sound ended (FBBD-47); text from [voiceEndText]. */
    const val VOICE_END = EventKinds.COACH_VOICE_END

    /**
     * The coach works first on the next-session focus of an earlier session (FBBD-20); text from
     * [focusText]. The coaching history (FBBD-180) reads from it which recommendation the session
     * worked on.
     */
    const val FOCUS = "coach_focus"

    /**
     * The coach works on the focus of the track's line plan (FBBD-306, UI-174 A), in place of [FOCUS];
     * text from [planText]: the session the plan was made from, its focus corners' CornerIds, most
     * time to find first, and their time to find together.
     */
    const val PLAN = "coach_plan"

    fun planText(sessionId: String, cornerIds: List<String>, gainSeconds: Double) =
        "source=$sessionId corners=${cornerIds.joinToString(",")} gain_s=" +
            fixed(gainSeconds, 2)

    /**
     * The coach went quiet for the rest of a lap (FBBD-202): text "lap=<n> reason=<[COOLING_DOWN] or
     * [PIT_LANE]>". So a log shows which laps it took as cool-down, not only that it said nothing (FBBD-244).
     */
    const val QUIET = "coach_quiet"
    const val COOLING_DOWN = "cooling_down"
    const val PIT_LANE = "pit_lane"

    fun quietText(lap: Int, reason: String) = "lap=$lap reason=$reason"

    /**
     * The coach is back on the lap it went quiet on (FBBD-254): after cooling down, two corners in a row
     * near their best (FBBD-252); after the pit lane, back up to speed. Text "lap=<n> after=<the
     * [QUIET] reason>". The passes it skipped while quiet are not used, the later ones are.
     */
    const val RESUMED = "coach_resumed"

    fun resumedText(lap: Int, after: String) = "lap=$lap after=$after"

    /** The text of a [FOCUS] event: the session the focus came from, and its corners' CornerIds, the primary first. */
    fun focusText(sessionId: String, cornerIds: List<String>) =
        "source=$sessionId corners=${cornerIds.joinToString(",")}"

    /**
     * The session whose focus the coach worked on in a session with [events]: the last [FOCUS] or
     * [PLAN] event's (a line plan's focus, UI-174 A), as a restarted coach logs it again; null when
     * the coach worked on none.
     */
    fun focusSource(events: List<RecorderEvent>): String? = events.filter { it.kind == FOCUS || it.kind == PLAN }
        .maxByOrNull { it.tMicros }?.let { split(it.text).first["source"] }?.takeIf { it.isNotBlank() }

    /**
     * The corners the coach worked on in a session with [events] when it worked on a line plan's focus
     * (UI-174 A): the last [PLAN] event's CornerIds, most time to find first. Empty when the last
     * [FOCUS] or [PLAN] event is a [FOCUS] (FBBD-20's own corners), or there is none.
     */
    fun planCorners(events: List<RecorderEvent>): List<String> = events.filter { it.kind == FOCUS || it.kind == PLAN }
        .maxByOrNull { it.tMicros }?.takeIf { it.kind == PLAN }
        ?.let { split(it.text).first["corners"] }?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** How many corner cues were said at each CornerId in a session with [events]; cues without one are left out. */
    fun cuesByCorner(events: List<RecorderEvent>): Map<String, Int> = events.asSequence()
        .filter { it.kind == CUE }
        .mapNotNull { parseCue(it.tMicros, it.text) }
        .filter { it.kind == CueKind.Corner }
        .mapNotNull { it.cornerId }
        .groupingBy { it }.eachCount()

    @Suppress("CyclomaticComplexMethod") // One optional field after another.
    fun cueText(cue: Cue): String = buildString {
        append("seq=").append(cue.seq)
        append(" lap=").append(cue.lap)
        append(" kind=").append(cue.kind.name)
        cue.corner?.let { append(" corner=").append(it) }
        cue.advice?.let { append(" advice=").append(adviceText(it)) }
        cue.also?.let { append(" also=").append(adviceText(it)) }
        if (cue.turns.isNotEmpty()) append(" turns=").append(cue.turns.joinToString(","))
        cue.before?.let { append(" before=").append(passText(it)) }
        cue.target?.let { append(" target=").append(passText(it)) }
        cue.zoneMeters?.let { append(" zone_m=").append(fixed(it, 1)) }
        cue.turn?.let { append(" turn=").append(it) }
        cue.cornerId?.let { append(" corner_id=").append(it) }
        if (cue.kind == CueKind.Corner) append(" conf=").append(cue.confidence.name)
        if (cue.doubts.isNotEmpty()) append(" why=").append(cue.doubts.joinToString(",") { it.code })
        cue.praise?.let { append(" praise=").append(it.name) }
        cue.praised?.let { append(" praised=").append(passText(it)) }
        if (cue.language != CoachLanguage.English) append(" lang=").append(cue.language.tag)
        append(" text=").append(cue.text)
    }

    /**
     * The text of a [VOICE] event: cue [seq], how it was said ([path], "live" or "clip"), when the
     * voice was asked for it ([askedMicros], session time) and where the sound went ([route],
     * "headset" or "speaker"). Then, when known (FBBD-47): when its first audio was ready
     * ([readyMicros], session time), the output [device] type, whether audio focus was granted
     * ([focus]), and the output's own delay ([outDelayMillis], FBBD-49): with [outDelaySource]
     * "measured" when it was measured at the session's start, even if 0; else the one set in the
     * coach settings, "none" when that is 0. [timedSource] is where the delay the cue was timed for
     * came from when it was asked (FBBD-255): the output may have changed, and been measured, since.
     */
    @Suppress("LongParameterList")
    fun voiceText(
        seq: Int,
        path: String,
        askedMicros: Long,
        route: String,
        readyMicros: Long? = null,
        device: String? = null,
        focus: Boolean? = null,
        outDelayMillis: Int? = null,
        outDelaySource: String? = null,
        timedSource: String? = null,
    ) = buildString {
        append("seq=$seq path=$path asked_us=$askedMicros route=$route")
        readyMicros?.let { append(" ready_us=").append(it) }
        device?.let { append(" device=").append(it) }
        focus?.let { append(" focus=").append(if (it) "granted" else "refused") }
        val measured = outDelaySource == AudioDelayUsed.Measured
        append(" out_delay_ms=").append(outDelayMillis?.takeIf { measured || it > 0 }?.toString() ?: "none")
        outDelaySource?.let { append(" out_delay_src=").append(it) }
        timedSource?.let { append(" timed_src=").append(it) }
    }

    /** The text of a [VOICE_END] event: cue [seq]'s sound [end]ed: "done", "stopped" (cut off) or "error". */
    fun voiceEndText(seq: Int, end: String) = "seq=$seq end=$end"

    fun resultText(r: CueResult): String = buildString {
        append("seq=").append(r.cue.seq)
        append(" corner=").append(r.after.corner)
        append(" lap=").append(r.after.lap)
        append(" improved=").append(r.improved)
        append(" gain_us=").append(r.gainMicros)
        append(" after=").append(passText(r.after))
        append(" conf=").append(r.confidence.name)
    }

    /** A cue read back from its event; null if the text is not one. */
    fun parseCue(tMicros: Long, text: String): Cue? = runCatching {
        val (fields, spoken) = split(text)
        val corner = fields["corner"]?.toInt()
        Cue(
            seq = fields.getValue("seq").toInt(),
            tMicros = tMicros,
            lap = fields.getValue("lap").toInt(),
            kind = CueKind.valueOf(fields.getValue("kind")),
            text = spoken,
            corner = corner,
            advice = fields["advice"]?.let(::parseAdvice),
            also = fields["also"]?.let(::parseAdvice),
            turns = fields["turns"]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
            before = corner?.let { c -> fields["before"]?.let { parsePass(c, it) } },
            target = corner?.let { c -> fields["target"]?.let { parsePass(c, it) } },
            zoneMeters = fields["zone_m"]?.toDouble(),
            turn = fields["turn"]?.toInt(),
            cornerId = fields["corner_id"],
            // Cues logged before FBBD-177 were all said with their numbers.
            confidence = Confidence.parse(fields["conf"]) ?: Confidence.HIGH,
            doubts = fields["why"]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
                .map { CueDoubt(it, CueDoubts.cap(it), CueDoubts.text(it)) },
            praise = fields["praise"]?.let(Praise::valueOf),
            praised = corner?.let { c -> fields["praised"]?.let { parsePass(c, it) } },
            language = CoachLanguage.entries.firstOrNull { it.tag == fields["lang"] } ?: CoachLanguage.English,
        )
    }.getOrNull()

    /**
     * A result read back: the cue's [seq], the pass after it, whether it improved, the gain, and how
     * far the pass after it can be trusted ([confidence], FBBD-177).
     */
    data class Result(
        val seq: Int,
        val after: CornerPass,
        val improved: Boolean,
        val gainMicros: Long,
        val confidence: Confidence,
    )

    fun parseResult(text: String): Result? = runCatching {
        val (f, _) = split(text)
        val corner = f.getValue("corner").toInt()
        val after = parsePass(corner, f.getValue("after")).copy(lap = f.getValue("lap").toInt())
        Result(
            seq = f.getValue("seq").toInt(),
            after = after,
            improved = f.getValue("improved").toBooleanStrict(),
            gainMicros = f.getValue("gain_us").toLong(),
            // A result logged before FBBD-177 has no grade: not known to be High, so no numbers for it.
            confidence = Confidence.parse(f["conf"]) ?: Confidence.MEDIUM,
        )
    }.getOrNull()

    private fun split(text: String): Pair<Map<String, String>, String> {
        val at = text.indexOf(" text=").let { if (it < 0) text.length else it }
        val fields = text.substring(0, at).split(' ').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val spoken = if (at < text.length) text.substring(at + " text=".length) else ""
        return fields to spoken
    }

    /**
     * "brake_later:10", "brake_later:10:vague" for advice said without its number (FBBD-177), with
     * its target "brake_later:10:at=100" (FBBD-242); "brake_earlier:10:at=120"; "carry:5:goal=60".
     * Before FBBD-242 brake_earlier had no number and nothing had a target; those still parse.
     * The line and the throttle (FBBD-307): "line:2:turn=Left" (metres tighter, negative wider, at a
     * left-hand corner) and
     * "throttle:5:after=5" (metres earlier, negative later; the target metres after the apex).
     * The hands (FBBD-321): "steer_less", "steer_more", "steer_smooth", "oversteer" and
     * "straighten:10" (metres earlier off full lock). The gear (FBBD-326): "gear:2".
     */
    private fun adviceText(a: Advice): String {
        val (kind, number, target) = when (a) {
            is Advice.BrakeLater -> Triple("brake_later", a.meters, a.at?.let { "$At$it" })
            is Advice.BrakeEarlier -> Triple("brake_earlier", a.meters, a.at?.let { "$At$it" })
            is Advice.CarrySpeed -> Triple("carry", a.kmh, a.goalKmh?.let { "$Goal$it" })
            is Advice.Line ->
                Triple("line", a.tighterMeters, a.turn.takeIf { it != Turn.Straight }?.let { "$Dir${it.name}" })
            is Advice.Throttle -> Triple("throttle", a.earlierMeters, a.afterApex?.let { "$After$it" })
            is Advice.Steer -> steerText(a.what)
            is Advice.Gear -> Triple("gear", a.gear, null)
        }
        return listOfNotNull(kind, number?.toString(), Vague.takeUnless { a.precise }, target).joinToString(":")
    }

    private fun steerText(w: SteerAdvice): Triple<String, Int?, String?> = when (w) {
        SteerAdvice.Less -> Triple("steer_less", null, null)
        SteerAdvice.More -> Triple("steer_more", null, null)
        SteerAdvice.Smoother -> Triple("steer_smooth", null, null)
        SteerAdvice.Oversteer -> Triple("oversteer", null, null)
        is SteerAdvice.OpenEarlier -> Triple("straighten", w.meters, null)
    }

    @Suppress("CyclomaticComplexMethod") // One branch per kind of advice.
    private fun parseAdvice(s: String): Advice {
        val parts = s.split(':')
        val rest = parts.drop(1)
        val number = rest.firstOrNull()?.toIntOrNull()
        val precise = Vague !in rest
        fun tagged(tag: String) = rest.firstOrNull { it.startsWith(tag) }?.removePrefix(tag)?.toInt()
        return when (parts[0]) {
            "brake_later" -> Advice.BrakeLater(requireNotNull(number), precise, tagged(At))
            "brake_earlier" -> Advice.BrakeEarlier(number, precise, tagged(At))
            "carry" -> Advice.CarrySpeed(requireNotNull(number), precise, tagged(Goal))
            "line" -> Advice.Line(
                requireNotNull(number), precise,
                rest.firstOrNull { it.startsWith(Dir) }?.removePrefix(Dir)?.let(Turn::valueOf) ?: Turn.Straight,
            )
            "throttle" -> Advice.Throttle(requireNotNull(number), precise, tagged(After))
            "steer_less" -> Advice.Steer(SteerAdvice.Less, precise)
            "steer_more" -> Advice.Steer(SteerAdvice.More, precise)
            "steer_smooth" -> Advice.Steer(SteerAdvice.Smoother, precise)
            "oversteer" -> Advice.Steer(SteerAdvice.Oversteer, precise)
            "straighten" -> Advice.Steer(SteerAdvice.OpenEarlier(requireNotNull(number)), precise)
            "gear" -> Advice.Gear(requireNotNull(number), precise)
            else -> throw IllegalArgumentException("unknown advice $s")
        }
    }

    private const val Vague = "vague"
    private const val At = "at="
    private const val Goal = "goal="
    private const val After = "after="
    private const val Dir = "turn="

    /**
     * "lap,brakeAt,apexAt,entryKmh,minKmh,timeMicros", distances and speeds to 0.1; then (FBBD-307)
     * ",apexOffset,pickupAt,apexMicros", each empty when not known; then with the wheel (FBBD-321)
     * ",peakDeg,peakAt,turnInAt,unwindAt,corrections,countersteer,pushDeg", countersteer 1 or 0.
     * Passes logged before have six or nine fields.
     */
    private fun passText(p: CornerPass) = listOf(
        p.lap.toString(), fixed(p.brakeAt, 1), fixed(p.apexAt, 1), fixed(p.entryKmh, 1), fixed(p.minKmh, 1),
        p.timeMicros.toString(),
    ).joinToString(",") + listOf(
        p.apexOffsetMeters?.let { fixed(it, 2) },
        p.pickupAt?.let { fixed(it, 1) },
        p.apexMicros?.toString(),
    ).joinToString("") { "," + it.orEmpty() } + p.steering?.let(::steeringText).orEmpty()

    private fun steeringText(s: SteeringPass): String = listOf(
        fixed(s.peakDeg, 1),
        fixed(s.peakAt, 1),
        fixed(s.turnInAt, 1),
        s.unwindAt?.let { fixed(it, 1) },
        s.corrections.toString(),
        if (s.countersteer) "1" else "0",
        s.pushDeg?.let { fixed(it, 1) },
    ).joinToString("") { "," + it.orEmpty() }

    private fun parseSteering(v: List<String>): SteeringPass = SteeringPass(
        peakDeg = v[0].toDouble(),
        peakAt = v[1].toDouble(),
        turnInAt = v[2].toDouble(),
        unwindAt = v[3].toDoubleOrNull(),
        corrections = v[4].toInt(),
        countersteer = v[5] == "1",
        pushDeg = v[6].toDoubleOrNull(),
    )

    private fun parsePass(corner: Int, s: String): CornerPass {
        val v = s.split(',')
        require(v.size == PassFields || v.size == LinePassFields || v.size == SteerPassFields) {
            "pass needs $PassFields, $LinePassFields or $SteerPassFields fields"
        }
        val next = v.iterator()
        return CornerPass(
            corner = corner,
            lap = next.next().toInt(),
            brakeAt = next.next().toDouble(),
            apexAt = next.next().toDouble(),
            entryKmh = next.next().toDouble(),
            minKmh = next.next().toDouble(),
            timeMicros = next.next().toLong(),
            apexOffsetMeters = if (next.hasNext()) next.next().toDoubleOrNull() else null,
            pickupAt = if (next.hasNext()) next.next().toDoubleOrNull() else null,
            apexMicros = if (next.hasNext()) next.next().toLongOrNull() else null,
            steering = v.takeIf { it.size == SteerPassFields }
                ?.let { parseSteering(it.subList(LinePassFields, it.size)) },
        )
    }

    private const val PassFields = 6
    private const val LinePassFields = 9
    private const val SteerPassFields = 16
}
