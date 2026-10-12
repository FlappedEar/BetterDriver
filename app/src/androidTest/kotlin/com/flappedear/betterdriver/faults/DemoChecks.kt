package com.flappedear.betterdriver.faults

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.fake.FakeGnssEvents
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

private const val MicrosPerMilli = 1_000L
private const val MillisPerSecond = 1_000L

/** One Demo GNSS fix of a session: its fix number and when the recorder stamped it. */
class DemoFix(val n: Long, val tMicros: Long, val set: SampleSet)

/** A stretch with no fixes: the last fix before it and the first after it. */
class FixGap(val before: DemoFix, val after: DemoFix) {
    val missing: Long get() = after.n - before.n - 1
    val micros: Long get() = after.tMicros - before.tMicros
}

/**
 * The Demo GNSS fixes of [data] in time order, each checked against the fix [FakeGnss] sends for its
 * number (worked out from its time stamp), so a changed, repeated or out-of-order fix fails.
 */
fun demoFixes(data: SessionData, gnss: FakeGnss = FakeGnss()): List<DemoFix> {
    val periodMs = MillisPerSecond.toDouble() / gnss.rateHz
    val fixes = data.sampleSets
        .filter { s -> s.values.any { it.channelId == GnssChannels.UTC_MS.id } }
        .sortedBy { it.tMicros }
        .map { s ->
            val utc = s.values.first { it.channelId == GnssChannels.UTC_MS.id }.value
            DemoFix(((utc - gnss.startUtcMillis) / periodMs).roundToLong(), s.tMicros, s)
        }
    fixes.forEach { assertEquals("fix ${it.n} of ${data.id}", gnss.fix(it.n), it.set.values) }
    fixes.zipWithNext().forEach { (a, b) -> assertTrue("fix ${b.n} after ${a.n} in ${data.id}", b.n > a.n) }
    return fixes
}

/** Where fix numbers jump: fixes Demo GNSS did not deliver into the session. */
fun fixGaps(fixes: List<DemoFix>): List<FixGap> =
    fixes.zipWithNext().filter { (a, b) -> b.n != a.n + 1 }.map { (a, b) -> FixGap(a, b) }

/** The times Demo GNSS was switched off: each drop with the return after it (null if it never came). */
fun switchedOff(data: SessionData): List<Pair<RecorderEvent, RecorderEvent?>> {
    val links = data.events.filter { it.kind == FakeGnssEvents.DISCONNECTED || it.kind == FakeGnssEvents.CONNECTED }
        .sortedBy { it.tMicros }
    return links.withIndex().filter { it.value.kind == FakeGnssEvents.DISCONNECTED }.map { (i, drop) ->
        drop to links.getOrNull(i + 1)?.takeIf { it.kind == FakeGnssEvents.CONNECTED }
    }
}

/**
 * 0 samples lost (FBBD-34, FBBD-69): every gap in the fix numbers is one where Demo GNSS was switched
 * off, as the session's own events say, and no fix arrived late either: apart from those, fixes are
 * at most [maxGapMs] apart. Returns the fixes.
 */
fun checkNoFixLost(data: SessionData, maxGapMs: Long = MaxArrivalGapMs): List<DemoFix> {
    val fixes = demoFixes(data)
    assertTrue("no fixes in ${data.id}", fixes.isNotEmpty())
    val off = switchedOff(data)
    for (gap in fixGaps(fixes)) {
        val explained = off.any { (drop, _) -> drop.tMicros in gap.before.tMicros..gap.after.tMicros }
        assertTrue(
            "${gap.missing} fixes lost after fix ${gap.before.n} in ${data.id}, with Demo GNSS on",
            explained,
        )
    }
    val late = fixes.zipWithNext().filter { (a, b) -> b.n == a.n + 1 }.maxOfOrNull { (a, b) -> b.tMicros - a.tMicros }
    val lateMs = (late ?: 0L) / MicrosPerMilli
    assertTrue("fixes $lateMs ms apart in ${data.id}", lateMs < maxGapMs)
    return fixes
}

/**
 * A valid VBO (FBBD-14, FBBD-34): the export of [data] has CRLF lines, its sections in order, every
 * data row as wide as the column names, times that never go back, and one row per fix. Returns the rows.
 */
fun checkVbo(data: SessionData, fixes: Int): Int {
    val text = ByteArrayOutputStream().also { VboExporter.export(data, it) }.toString(Charsets.UTF_8.name())
    assertTrue("a line without CR in the VBO of ${data.id}", !Regex("(?<!\r)\n").containsMatchIn(text))
    val lines = text.split("\r\n")
    val sections = lines.filter { it.startsWith("[") && it.endsWith("]") }
    val wanted = listOf("[header]", "[column names]", "[data]")
    assertEquals("VBO sections of ${data.id}", wanted, sections.filter { it in wanted })
    val columns = lines[lines.indexOf("[column names]") + 1].split(" ")
    val rows = lines.drop(lines.indexOf("[data]") + 1).filter { it.isNotBlank() }.map { it.split(" ") }
    rows.forEachIndexed { i, r -> assertEquals("VBO row $i of ${data.id}", columns.size, r.size) }
    val time = columns.indexOf("time")
    assertTrue("no time column in the VBO of ${data.id}", time >= 0)
    val seconds = rows.map { vboSeconds(it[time]) }
    seconds.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("VBO time goes back at row ${i + 1}", b >= a) }
    assertEquals("VBO rows of ${data.id} (0 samples lost in the export)", fixes, rows.size)
    return rows.size
}

/** "hhmmss.sss" to seconds of the day. */
private fun vboSeconds(t: String): Double {
    val h = t.substring(0, 2).toInt()
    val m = t.substring(2, 4).toInt()
    return h * SecondsPerHour + m * SecondsPerMinute + t.substring(4).toDouble()
}

private const val SecondsPerHour = 3_600.0
private const val SecondsPerMinute = 60.0

/** Fixes come at 25 Hz: two fixes in a row a second apart would be a stall. */
const val MaxArrivalGapMs = 1_000L
