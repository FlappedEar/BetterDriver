package com.flappedear.betterdriver.recorder.log

import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLifeLogTest {
    @Test
    fun `a cold start is logged and read back`() {
        assertEquals("cold ms=812", AppLifeLog.coldStart(812))
        assertEquals(812L, AppLifeLog.parseColdStart(AppLifeLog.coldStart(812)))
        assertNull(AppLifeLog.parseColdStart("warm ms=200"))
        assertNull(AppLifeLog.parseColdStart("cold"))
    }

    @Test
    fun `a check of Android's exit record says whether it could be read`() {
        assertEquals("check sdk=34 new=2", AppLifeLog.check(34, 2))
        assertEquals("check sdk=29 unavailable", AppLifeLog.check(29, null))
        assertEquals(true, AppLifeLog.parseCheck(AppLifeLog.check(34, 0)))
        assertEquals(false, AppLifeLog.parseCheck(AppLifeLog.check(29, null)))
        // Android 11+ that refused the read: no read, not a read of nothing.
        assertEquals("check sdk=34 failed", AppLifeLog.checkFailed(34))
        assertEquals(false, AppLifeLog.parseCheck(AppLifeLog.checkFailed(34)))
        assertNull(AppLifeLog.parseCheck("exit utc_ms=1 pid=2 process=x reason=ANR importance=100 desc="))
    }

    @Test
    fun `a process end is one line and reads back whole, its description with spaces`() {
        val exit = ProcessExit(
            1_760_000_123_456, 4711, "com.flappedear.betterdriver", ProcessExit.ANR, 100,
            "Input dispatching timed out\n(Waited 5001ms)",
        )
        val message = AppLifeLog.exit(exit)
        assertEquals(
            "exit utc_ms=1760000123456 pid=4711 process=com.flappedear.betterdriver reason=ANR importance=100 " +
                "desc=Input dispatching timed out (Waited 5001ms)",
            message,
        )
        val oneLine = exit.copy(description = "Input dispatching timed out (Waited 5001ms)")
        assertEquals(oneLine, AppLifeLog.parseExit(message))
        // Through the log file's own line format.
        val line = LogLine(1_760_000_200_000, 1L, "main", 1, "exits", 'I', AppLifeLog.EXIT, null, message)
        val back = LogLine.parse(line.format(ZoneOffset.UTC))!!
        assertEquals(AppLifeLog.EXIT, back.tag)
        assertEquals(ProcessExit.ANR, AppLifeLog.parseExit(back.message)?.reason)
        assertNull(AppLifeLog.parseExit("exit pid=1 reason=ANR"))
        assertNull(AppLifeLog.parseExit("check sdk=34 new=0"))
    }
}
