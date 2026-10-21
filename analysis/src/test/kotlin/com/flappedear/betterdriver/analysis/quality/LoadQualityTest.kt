package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.log.LoadLine
import com.flappedear.betterdriver.recorder.log.LogLine
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The App load section (FBBD-281) from the processes' once-a-minute load lines. */
class LoadQualityTest {
    private fun line(process: String, message: String, tag: String = LoadLine.TAG) =
        LogLine(1_760_000_000_000L, null, process, 1, "load", 'I', tag, "s", message)

    private fun load(process: String, cpuMs: Long, gc: Int, gcMs: Int, allocMb: Double) =
        line(
            process,
            "load: cpu_pct=${cpuMs / 600.0} cpu_ms=$cpuMs wall_ms=60000 gc=$gc gc_ms=$gcMs alloc_mb=$allocMb " +
                "heap_mb=20/256 threads=30",
        )

    @Test
    fun `each process gets its mean CPU over its minutes`() {
        val section = loadSection(
            listOf(
                load("recorder", cpuMs = 6_000, gc = 1, gcMs = 5, allocMb = 3.0),
                load("recorder", cpuMs = 12_000, gc = 2, gcMs = 7, allocMb = 5.0),
                load("main", cpuMs = 21_000, gc = 4, gcMs = 40, allocMb = 80.0),
                line("camera", "camera frames: 3600 in 60.0 s = 60.0 fps", tag = "PhoneCamera"),
            ),
        )!!
        assertEquals("App load", section.title)
        assertEquals(listOf(loadCpuId("main"), loadCpuId("recorder")), section.metrics.map { it.id })
        val recorder = section.metrics.single { it.id == loadCpuId("recorder") }
        assertEquals(15.0, recorder.value)
        assertEquals(Verdict.INFO, recorder.verdict)
        assertTrue("busiest minute 20.0 %" in recorder.how, recorder.how)
        assertTrue("3 GC (12 ms); 4.0 MB allocated a minute" in recorder.how, recorder.how)
    }

    @Test
    fun `a log without load lines has no section`() {
        assertNull(loadSection(listOf(line("recorder", "start", tag = "Recording"))))
    }
}
