package com.flappedear.betterdriver.coach

import java.util.Locale

/**
 * How cues and their results are written into the session as events (FBBD-98), so the cue review
 * (FBBD-28, FBBD-99) reads exactly what was said and what happened next. Text is `key=value` pairs
 * separated by spaces; the spoken text comes last, after `text=`, and may hold spaces.
 */
object CoachEvents {
    /** A cue was spoken; its event time is when the coach asked for it. */
    const val CUE = "coach_cue"

    /** The pass right after a corner cue. */
    const val RESULT = "coach_result"

    /** The coach could not speak: no voice, or a cue came too late to say. Text is the reason. */
    const val PROBLEM = "coach_problem"

    fun cueText(cue: Cue): String = buildString {
        append("seq=").append(cue.seq)
        append(" lap=").append(cue.lap)
        append(" kind=").append(cue.kind.name)
        cue.corner?.let { append(" corner=").append(it) }
        cue.advice?.let { append(" advice=").append(adviceText(it)) }
        cue.before?.let { append(" before=").append(passText(it)) }
        cue.target?.let { append(" target=").append(passText(it)) }
        append(" text=").append(cue.text)
    }

    fun resultText(r: CueResult): String = buildString {
        append("seq=").append(r.cue.seq)
        append(" corner=").append(r.after.corner)
        append(" lap=").append(r.after.lap)
        append(" improved=").append(r.improved)
        append(" gain_us=").append(r.gainMicros)
        append(" after=").append(passText(r.after))
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
            before = corner?.let { c -> fields["before"]?.let { parsePass(c, it) } },
            target = corner?.let { c -> fields["target"]?.let { parsePass(c, it) } },
        )
    }.getOrNull()

    /** A result read back: the cue's [seq], the pass after it, whether it improved and the gain. */
    data class Result(val seq: Int, val after: CornerPass, val improved: Boolean, val gainMicros: Long)

    fun parseResult(text: String): Result? = runCatching {
        val (f, _) = split(text)
        val corner = f.getValue("corner").toInt()
        val after = parsePass(corner, f.getValue("after")).copy(lap = f.getValue("lap").toInt())
        Result(
            seq = f.getValue("seq").toInt(),
            after = after,
            improved = f.getValue("improved").toBooleanStrict(),
            gainMicros = f.getValue("gain_us").toLong(),
        )
    }.getOrNull()

    private fun split(text: String): Pair<Map<String, String>, String> {
        val at = text.indexOf(" text=").let { if (it < 0) text.length else it }
        val fields = text.substring(0, at).split(' ').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val spoken = if (at < text.length) text.substring(at + " text=".length) else ""
        return fields to spoken
    }

    private fun adviceText(a: Advice) = when (a) {
        is Advice.BrakeLater -> "brake_later:${a.meters}"
        Advice.BrakeEarlier -> "brake_earlier"
        is Advice.CarrySpeed -> "carry:${a.kmh}"
    }

    private fun parseAdvice(s: String): Advice = when (s.substringBefore(':')) {
        "brake_later" -> Advice.BrakeLater(s.substringAfter(':').toInt())
        "brake_earlier" -> Advice.BrakeEarlier
        "carry" -> Advice.CarrySpeed(s.substringAfter(':').toInt())
        else -> throw IllegalArgumentException("unknown advice $s")
    }

    /** "lap,brakeAt,apexAt,entryKmh,minKmh,timeMicros", distances and speeds to 0.1. */
    private fun passText(p: CornerPass) = String.format(
        Locale.ROOT,
        "%d,%.1f,%.1f,%.1f,%.1f,%d",
        p.lap, p.brakeAt, p.apexAt, p.entryKmh, p.minKmh, p.timeMicros,
    )

    private fun parsePass(corner: Int, s: String): CornerPass {
        val v = s.split(',')
        require(v.size == PassFields) { "pass needs $PassFields fields" }
        val next = v.iterator()
        return CornerPass(
            corner = corner,
            lap = next.next().toInt(),
            brakeAt = next.next().toDouble(),
            apexAt = next.next().toDouble(),
            entryKmh = next.next().toDouble(),
            minKmh = next.next().toDouble(),
            timeMicros = next.next().toLong(),
        )
    }

    private const val PassFields = 6
}
