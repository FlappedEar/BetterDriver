package com.flappedear.betterdriver.debug

import java.util.Locale

private const val NanosPerMilli = 1_000_000L
private const val NanosPerSecond = 1_000_000_000L
private const val Percent = 100.0

/** How often [JankMinutes] closes a summary: once a minute. */
const val JankPeriodNanos = 60 * NanosPerSecond

/**
 * The frames drawn in one stretch of a recording (FBBD-41): [frames] in all, [janky] of them slow
 * enough for JankStats to count as jank, and the slowest one. [seconds] is from the first frame to
 * where the stretch ended.
 */
data class JankSummary(val seconds: Long, val frames: Int, val janky: Int, val worstMs: Long) {
    /** The debug log line: "jank: 3 of 3542 frames (0.1%) in 60 s, slowest 84 ms". */
    fun line(): String {
        val share = if (frames == 0) 0.0 else janky * Percent / frames
        return "jank: $janky of $frames frames (" + String.format(Locale.ROOT, "%.1f", share) +
            "%) in $seconds s, slowest $worstMs ms"
    }
}

/**
 * Adds up the frames JankStats reports while a session records and closes a [JankSummary] every
 * [periodNanos] (FBBD-41). Frames are what moves it on: a minute ends with the first frame after it,
 * so a screen that draws nothing logs nothing. Frame times are JankStats' own (System.nanoTime).
 *
 * Thread-safe: JankStats may report from its own thread.
 */
class JankMinutes(private val periodNanos: Long = JankPeriodNanos) {
    private var startNanos = 0L
    private var endNanos = 0L
    private var frames = 0
    private var janky = 0
    private var worstNanos = 0L

    /** Counts one frame; returns the summary of the period it closed, if it started a new one. */
    @Synchronized
    fun add(frameStartNanos: Long, durationNanos: Long, jank: Boolean): JankSummary? {
        val closed = if (frames > 0 && frameStartNanos - startNanos >= periodNanos) take(frameStartNanos) else null
        if (frames == 0) startNanos = frameStartNanos
        frames++
        if (jank) janky++
        worstNanos = maxOf(worstNanos, durationNanos)
        endNanos = maxOf(endNanos, frameStartNanos + durationNanos)
        return closed
    }

    /** Closes the period now (the recording ended); null when no frame came since the last summary. */
    @Synchronized
    fun flush(): JankSummary? = if (frames == 0) null else take(endNanos)

    private fun take(untilNanos: Long): JankSummary {
        val summary = JankSummary(
            seconds = (untilNanos - startNanos).coerceAtLeast(0) / NanosPerSecond,
            frames = frames,
            janky = janky,
            worstMs = worstNanos / NanosPerMilli,
        )
        frames = 0
        janky = 0
        worstNanos = 0
        endNanos = 0
        return summary
    }
}

/**
 * Which StrictMode violations reach the debug log (FBBD-41). Each place is logged the first time,
 * then again at its 10th, 100th, 1000th… time with the count, so a violation inside a loop or a
 * frame cannot flood the log. At most [maxPlaces] places are followed; later ones only reach logcat.
 */
class ViolationCounts(private val maxPlaces: Int = DefaultMaxPlaces) {
    private val counts = HashMap<String, Int>()

    /** Counts one violation at [place]; returns its count when this one should be logged, else null. */
    @Synchronized
    fun count(place: String): Int? {
        val before = counts[place] ?: if (counts.size >= maxPlaces) return null else 0
        val n = before + 1
        counts[place] = n
        return n.takeIf { isPowerOfTen(it) }
    }

    private fun isPowerOfTen(n: Int): Boolean {
        var x = n
        while (x % Ten == 0) x /= Ten
        return x == 1
    }

    private companion object {
        const val DefaultMaxPlaces = 200
        const val Ten = 10
    }
}

/**
 * Where a violation happened, as one short key: its [kind] and the first frame of the app's own code
 * ([appPackage]), else the first frame. "DiskReadViolation at SetupRepo.<init>(SetupRepo.kt:40)".
 */
fun violationPlace(kind: String, frames: List<StackTraceElement>, appPackage: String): String {
    val frame = frames.firstOrNull { it.className.startsWith("$appPackage.") } ?: frames.firstOrNull()
        ?: return kind
    val cls = frame.className.substringAfterLast('.')
    return "$kind at $cls.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
}
