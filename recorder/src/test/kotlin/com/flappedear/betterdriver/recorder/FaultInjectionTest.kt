package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Child process for [FaultInjectionTest]: records fake GNSS until it is killed. */
object FaultInjectionChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val gnss = FakeGnss()
        val store = SessionStore(File(args[0]))
        val info = SessionInfo(gnss.startUtcMillis, track = "Fake ring", startFinish = gnss.startFinish)
        val rec = Recorder(store).start(info)
        val out = System.out
        rec.attach(FakeGnssSource(gnss) { n -> synchronized(out) { out.println(n); out.flush() } })
        Thread.sleep(Long.MAX_VALUE)
    }
}

/**
 * FBBD-72: kill the recorder process (SIGKILL, no shutdown hooks) at random points. Every recovered
 * session must export to a valid VBO holding every fix up to at most 1 s before the kill, in order
 * and unchanged.
 */
class FaultInjectionTest {
    @TempDir
    lateinit var tmp: File

    private val runs = 20
    private val gnss = FakeGnss()

    @Test
    fun `killing the recorder loses at most the last second`() {
        val random = Random(72)
        repeat(runs) { run ->
            val root = File(tmp, "run$run")
            val killAfterMs = random.nextLong(300, 2_500)
            val emitted = ConcurrentLinkedDeque<Pair<Long, Long>>() // (nanoTime, fix index)

            val java = File(System.getProperty("java.home"), "bin/java").path
            val proc = ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                FaultInjectionChild::class.java.name, root.path,
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val reader = Thread {
                proc.inputStream.bufferedReader().forEachLine { l ->
                    l.trim().toLongOrNull()?.let { emitted += System.nanoTime() to it }
                }
            }.apply { isDaemon = true; start() }

            // Wait for the first fix so JVM start-up time does not eat into the run.
            val deadline = System.nanoTime() + 20_000_000_000L
            while (emitted.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            check(emitted.isNotEmpty()) { "run $run: child produced no fixes" }
            Thread.sleep(killAfterMs)
            val killNanos = System.nanoTime()
            proc.destroyForcibly()
            check(proc.waitFor(10, TimeUnit.SECONDS))
            reader.join(2_000)

            // Every fix handed to the recorder more than 1 s before the kill must be on disk.
            val mustHave = emitted.filter { it.first <= killNanos - 1_000_000_000L }.maxOfOrNull { it.second } ?: -1L

            val store = SessionStore(root)
            val summary = store.list().single()
            assertEquals(SessionState.INTERRUPTED, summary.state, "run $run")
            store.recoverInterrupted()
            val data = store.read(summary.id)
            assertEquals(SessionState.RECOVERED, data.state)

            val fixes = data.sampleSets.map { it.values }
            assertTrue(
                fixes.size > mustHave,
                "run $run: kill after ${killAfterMs} ms kept ${fixes.size} fixes, needed ${mustHave + 1}",
            )
            fixes.forEachIndexed { i, v -> assertEquals(gnss.fix(i.toLong()), v, "run $run fix $i") }

            val out = ByteArrayOutputStream()
            VboExporter.export(data, out)
            val vbo = VboValidator.validate(out.toString(Charsets.UTF_8))
            assertEquals(fixes.size, vbo.rows.size, "run $run")
        }
    }
}
