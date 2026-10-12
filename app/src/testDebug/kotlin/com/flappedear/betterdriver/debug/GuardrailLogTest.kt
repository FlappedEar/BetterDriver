package com.flappedear.betterdriver.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardrailLogTest {
    private val ms = 1_000_000L
    private val s = 1_000_000_000L

    @Test
    fun aMinuteClosesWithTheFirstFrameAfterIt() {
        val minutes = JankMinutes()
        assertNull(minutes.add(0, 16 * ms, jank = false))
        assertNull(minutes.add(30 * s, 84 * ms, jank = true))
        assertNull(minutes.add(59 * s, 16 * ms, jank = false))
        val closed = minutes.add(61 * s, 16 * ms, jank = false)
        assertEquals(JankSummary(seconds = 61, frames = 3, janky = 1, worstMs = 84), closed)
        // The frame that closed it opens the next minute.
        assertEquals(JankSummary(seconds = 0, frames = 1, janky = 0, worstMs = 16), minutes.flush())
    }

    @Test
    fun flushEndsWhereTheLastFrameEndedAndThenHasNothing() {
        val minutes = JankMinutes()
        minutes.add(10 * s, 20 * ms, jank = true)
        minutes.add(25 * s, 500 * ms, jank = true)
        assertEquals(JankSummary(seconds = 15, frames = 2, janky = 2, worstMs = 500), minutes.flush())
        assertNull(minutes.flush())
    }

    @Test
    fun noFramesNoSummary() {
        assertNull(JankMinutes().flush())
    }

    @Test
    fun theLineSaysHowManyOfHowMany() {
        assertEquals(
            "jank: 3 of 3542 frames (0.1%) in 60 s, slowest 84 ms",
            JankSummary(seconds = 60, frames = 3542, janky = 3, worstMs = 84).line(),
        )
        assertEquals("jank: 0 of 0 frames (0.0%) in 0 s, slowest 0 ms", JankSummary(0, 0, 0, 0).line())
    }

    @Test
    fun aPlaceIsLoggedOnceThenAtTenHundredThousand() {
        val counts = ViolationCounts()
        val logged = (1..1500).mapNotNull { counts.count("DiskReadViolation at A.b(A.kt:1)") }
        assertEquals(listOf(1, 10, 100, 1000), logged)
    }

    @Test
    fun placesBeyondTheLimitAreNotFollowed() {
        val counts = ViolationCounts(maxPlaces = 2)
        assertEquals(1, counts.count("a"))
        assertEquals(1, counts.count("b"))
        assertNull(counts.count("c"))
        // Places already followed still count.
        repeat(8) { counts.count("a") }
        assertEquals(10, counts.count("a"))
    }

    @Test
    fun thePlaceIsTheFirstFrameOfTheAppsOwnCode() {
        val frames = listOf(
            StackTraceElement("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk", "StrictMode.java", 1),
            StackTraceElement("java.io.File", "exists", "File.java", 2),
            StackTraceElement("com.flappedear.betterdriver.shell.SetupRepo", "<init>", "SetupRepo.kt", 40),
            StackTraceElement("com.flappedear.betterdriver.MainActivity", "onCreate", "MainActivity.kt", 9),
        )
        assertEquals(
            "DiskReadViolation at SetupRepo.<init>(SetupRepo.kt:40)",
            violationPlace("DiskReadViolation", frames, "com.flappedear.betterdriver"),
        )
    }

    @Test
    fun withoutAppCodeThePlaceIsTheFirstFrame() {
        val frames = listOf(StackTraceElement("java.io.FileInputStream", "<init>", "FileInputStream.java", 3))
        assertEquals(
            "DiskReadViolation at FileInputStream.<init>(FileInputStream.java:3)",
            violationPlace("DiskReadViolation", frames, "com.flappedear.betterdriver"),
        )
        assertEquals("LeakedClosableViolation", violationPlace("LeakedClosableViolation", emptyList(), "x"))
    }
}
