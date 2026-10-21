package com.flappedear.betterdriver.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadCpuTest {
    @Test
    fun ticksAreUserPlusSystem() {
        val stat = "4900 (CameraX-core_c) S 4815 4815 0 0 -1 1077936192 120 0 0 0 1500 320 0 0 10 -10 41 0"
        assertEquals("CameraX-core_c" to 1820L, ThreadCpu.threadTicks(stat))
    }

    @Test
    fun aNameWithSpacesAndParentheses() {
        val stat = "4901 (GL (1) x) R 4815 4815 0 0 -1 0 0 0 0 0 7 3 0 0"
        assertEquals("GL (1) x" to 10L, ThreadCpu.threadTicks(stat))
        assertNull(ThreadCpu.threadTicks("garbage"))
    }

    @Test
    fun busiestThreadsInPercentOfOneCore() {
        val before = mapOf(1 to 100L, 2 to 0L, 3 to 50L)
        val now = mapOf(
            1 to ("CameraX-core_camera_0" to 1_300L),
            2 to ("binder:4815_1" to 60L),
            3 to ("binder:4815_1" to 110L),
            4 to ("GLThread" to 600L),
            5 to ("idle" to 0L),
        )
        // The process used 2,100 ticks: 1,920 by live threads, 180 by threads that ended.
        assertEquals(
            "camera threads: CameraX-core_camera_0=20.0% GLThread=10.0% binder:4815_1=2.0% exited=3.0%",
            ThreadCpu.busiest(before, now, totalTicks = 2_100, wallMillis = 60_000, ticksPerSecond = 100),
        )
        assertEquals(
            "camera threads: none exited=0.0%",
            ThreadCpu.busiest(emptyMap(), mapOf(5 to ("idle" to 0L)), 0, 60_000, 100),
        )
    }
}
