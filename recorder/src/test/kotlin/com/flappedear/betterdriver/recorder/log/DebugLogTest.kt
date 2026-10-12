package com.flappedear.betterdriver.recorder.log

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugLogTest {
    @TempDir
    lateinit var dir: File

    private val logFile get() = File(dir, "betterdriver.log")

    private fun log(process: String = "main", background: Boolean = false) =
        LogFile(logFile, process, pid = 7, clock = { 0L }, uptimeMicros = { 4_711_204_518L }, background = background)

    @Test
    fun writesOneLineWithBothClocksProcessAndSession() {
        log("recorder").write(DebugLog.INFO, "Camera", "4K · 60 started", null, "s1")
        val text = logFile.readText()
        val prefix = "1970-01-01T00:00:00.000Z 4711.204518 recorder 7 "
        assertTrue(text.startsWith(prefix), text)
        assertTrue(text.endsWith(" I Camera [s1]: 4K · 60 started\n"), text)
        val line = assertNotNull(LogLine.parse(text.trimEnd()))
        val thread = Thread.currentThread().name.replace(' ', '_')
        assertEquals(LogLine(0L, 4_711_204_518L, "recorder", 7, thread, 'I', "Camera", "s1", "4K · 60 started"), line)
    }

    @Test
    fun aLineOutsideASessionSaysSo() {
        log().write(DebugLog.WARN, "App", "no session: here", null, null)
        val line = assertNotNull(LogLine.parse(logFile.readText().trimEnd()))
        assertNull(line.session)
        assertEquals("no session: here", line.message)
    }

    @Test
    fun readsTheFilesOfOlderBuilds() {
        val text = "2026-10-12T10:56:54.251Z 13207 main W Thermal: status=light headroom=0.9 at 4K · 60"
        val line = assertNotNull(LogLine.parse(text, "camera"))
        assertEquals("camera", line.process)
        assertEquals("Thermal", line.tag)
        assertNull(line.uptimeMicros)
        assertEquals("status=light headroom=0.9 at 4K · 60", line.message)
    }

    @Test
    fun errorsCarryTheirStackTrace() {
        log().write(DebugLog.ERROR, "Crash", "uncaught", IllegalStateException("boom"), null)
        val text = logFile.readText()
        assertTrue("java.lang.IllegalStateException: boom" in text, text)
        assertTrue("\tat " in text, text)
        assertNull(LogLine.parse(text.lines()[1]))
    }

    @Test
    fun neverRotatesOrTrims() {
        val l = log()
        repeat(5_000) { l.write(DebugLog.INFO, "T", "line $it with some padding text to grow the file", null, null) }
        assertEquals(listOf("betterdriver.log"), dir.list().orEmpty().toList())
        assertEquals(5_000, logFile.readLines().size)
    }

    @Test
    fun processesAppendToTheSameFileWithoutMixingLines() {
        val writers = (1..4).map { p ->
            thread {
                val l = LogFile(logFile, "p$p", background = true)
                repeat(500) { l.write(DebugLog.INFO, "T", "process $p line $it", null, null) }
                l.close()
            }
        }
        writers.forEach { it.join() }
        val lines = logFile.readLines()
        assertEquals(2_000, lines.size)
        assertTrue(lines.all { LogLine.parse(it) != null })
    }

    @Test
    fun startsAgainAfterTheLogIsCleared() {
        val l = log()
        l.write(DebugLog.INFO, "T", "before", null, null)
        assertTrue(logFile.delete())
        l.write(DebugLog.INFO, "T", "after", null, null)
        assertEquals(1, logFile.readLines().size)
    }

    @Test
    fun flushWritesQueuedLinesInsideTheInstalledWrapper() {
        var wrapped = 0
        try {
            DebugLog.install(log(background = true))
            DebugLog.fileWrites = { write ->
                wrapped++
                write()
            }
            DebugLog.session = "s9"
            DebugLog.i("T", "queued")
            DebugLog.flush()
        } finally {
            DebugLog.fileWrites = null
            DebugLog.session = null
            DebugLog.install(null)
        }
        assertEquals(1, wrapped)
        val line = assertNotNull(LogLine.parse(logFile.readLines().single()))
        assertEquals("s9", line.session)
    }
}
