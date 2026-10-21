package com.flappedear.betterdriver.recorder.log

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoadLineTest {
    private val mb = 1L shl 20

    @Test
    fun `a minute of work is written and read back`() {
        val from = LoadSample(10_000, 2_000, 5, 100, 50 * mb, 0, 0, 0)
        val to = LoadSample(70_000, 9_380, 8, 145, 62 * mb + mb / 2, 34 * mb, 512 * mb, 41)
        val line = LoadLine.between(from, to)!!
        val text = line.text()
        assertEquals(
            "load: cpu_pct=12.3 cpu_ms=7380 wall_ms=60000 gc=3 gc_ms=45 alloc_mb=12.5 heap_mb=34/512 threads=41",
            text,
        )
        assertEquals(line.copy(cpuPercent = 12.3, allocatedMb = 12.5), LoadLine.parse(text))
    }

    @Test
    fun `no time between samples gives no line and other messages are not read`() {
        val s = LoadSample(10_000, 2_000, 5, 100, 50 * mb, 0, 0, 0)
        assertNull(LoadLine.between(s, s))
        assertNull(LoadLine.parse("camera frames: 3392 in 60.0 s"))
    }
}
