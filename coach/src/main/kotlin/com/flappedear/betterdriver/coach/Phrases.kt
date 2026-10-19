package com.flappedear.betterdriver.coach

/**
 * What the coach says (FBBD-97): short English phrases, numbers allowed (Arek, FBBD-6) when the
 * evidence is strong enough for them (FBBD-177). Corners are named by the track's corner numbers
 * (FBBD-172), the ones the lap analysis shows.
 */
object Phrases {
    fun corner(corner: Corner, advice: Advice): String {
        val name = "Turn ${corner.label}"
        return when (advice) {
            is Advice.BrakeLater ->
                if (advice.precise) "$name, brake ${advice.meters} metres later" else "$name, brake a little later"
            Advice.BrakeEarlier -> "$name, brake earlier"
            is Advice.CarrySpeed ->
                if (advice.precise) "$name, carry ${advice.kmh} more" else "$name, carry a little more speed"
        }
    }

    /** "Turn 3, that worked" or "Turn 3, best yet" (FBBD-243, UI-93 C). */
    fun praise(corner: Corner, praise: Praise): String = "Turn ${corner.label}, " + when (praise) {
        Praise.Worked -> "that worked"
        Praise.BestYet -> "best yet"
    }

    /** "1 32 point 4", read by text-to-speech as "one thirty-two point four"; "Best lap, ..." for a new best. */
    fun lapTime(millis: Long, best: Boolean): String {
        val tenths = (millis + HalfTenth) / MillisPerTenth
        val minutes = tenths / TenthsPerMinute
        val seconds = tenths % TenthsPerMinute / TenthsPerSecond
        val tenth = tenths % TenthsPerSecond
        val time = if (minutes > 0) {
            "$minutes ${"%02d".format(seconds)} point $tenth"
        } else {
            "$seconds point $tenth"
        }
        return if (best) "Best lap, $time" else time
    }

    /**
     * About how long text-to-speech takes to say [text] at normal rate: a fixed start plus a time per
     * word, counting a two-digit number as two words. An estimate; the audio delay setting covers lag.
     */
    fun durationMicros(text: String): Long {
        val words = text.split(' ', ',').filter { it.isNotBlank() }.sumOf { w ->
            if (w.all(Char::isDigit) && w.length >= 2 && w.toInt() > SpokenAsOneWord) 2L else 1L
        }
        return StartMicros + words * WordMicros
    }

    private const val HalfTenth = 50L
    private const val MillisPerTenth = 100L
    private const val TenthsPerSecond = 10L
    private const val TenthsPerMinute = 600L
    private const val SpokenAsOneWord = 20
    private const val StartMicros = 200_000L
    private const val WordMicros = 400_000L
}
