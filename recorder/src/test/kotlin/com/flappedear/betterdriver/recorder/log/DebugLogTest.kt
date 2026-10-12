package com.flappedear.betterdriver.recorder.log

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugLogTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun writesOneTimestampedLinePerCall() {
        val log = LogFile(dir, "main", clock = { 0L }, pid = 7)
        log.write(DebugLog.INFO, "Camera", "4K · 60 started", null)
        val text = File(dir, "main.log").readText()
        assertTrue(text.startsWith("1970-01-01T00:00:00.000Z 7 "), text)
        assertTrue(text.endsWith(" I Camera: 4K · 60 started\n"), text)
    }

    @Test
    fun errorsCarryTheirStackTrace() {
        val log = LogFile(dir, "main")
        log.write(DebugLog.ERROR, "Crash", "uncaught", IllegalStateException("boom"))
        val text = File(dir, "main.log").readText()
        assertTrue("java.lang.IllegalStateException: boom" in text, text)
        assertTrue("\tat " in text, text)
    }

    @Test
    fun rotatesAndKeepsOnlyTheNewestFiles() {
        val log = LogFile(dir, "camera", LogFile.Rotation(maxBytes = 200, keep = 2))
        repeat(40) { log.write(DebugLog.INFO, "T", "line $it with some padding text", null) }
        val names = dir.list().orEmpty().sorted()
        assertEquals(listOf("camera.1.log", "camera.2.log", "camera.log"), names)
        assertTrue("line 39" in File(dir, "camera.log").readText() || "line 39" in File(dir, "camera.1.log").readText())
        assertFalse("line 0 " in File(dir, "camera.2.log").readText())
    }

    @Test
    fun writesNothingWhileOff() {
        var on = false
        val log = LogFile(dir, "main", enabled = { on })
        log.write(DebugLog.INFO, "T", "hidden", null)
        on = true
        log.write(DebugLog.INFO, "T", "shown", null)
        val text = File(dir, "main.log").readText()
        assertFalse("hidden" in text)
        assertTrue("shown" in text)
    }

    @Test
    fun appendsAfterAReopen() {
        LogFile(dir, "main").apply { write(DebugLog.INFO, "T", "first", null); close() }
        LogFile(dir, "main").write(DebugLog.INFO, "T", "second", null)
        val lines = File(dir, "main.log").readLines()
        assertEquals(2, lines.size)
    }

    @Test
    fun fileWritesRunInsideTheInstalledWrapper() {
        var wrapped = 0
        try {
            DebugLog.install(LogFile(dir, "main"))
            DebugLog.fileWrites = { write ->
                wrapped++
                write()
            }
            DebugLog.i("T", "wrapped")
            DebugLog.fileWrites = null
            DebugLog.i("T", "direct")
        } finally {
            DebugLog.fileWrites = null
            DebugLog.install(null)
        }
        assertEquals(1, wrapped)
        assertEquals(2, File(dir, "main.log").readLines().size)
    }
}
