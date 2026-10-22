@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentShape
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.timing.track.corrected
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit A02: the editor, the session analysis and the coach references each make their own
 * [TrackStore] on one folder and write from their own threads. Every round releases all writers at
 * once with a barrier; without a lock across read, check and write, saves read the same revision and
 * overwrite each other, and several corrections are accepted against one expected identity.
 */
@Timeout(60)
class TrackStoreConcurrencyTest {
    private val circuit = FakeCircuit()
    private val bend = PI * circuit.radius
    private val bend1 = 200.0..200.0 + bend
    private val bend2 = bend1.endInclusive + 400.0..bend1.endInclusive + 400.0 + bend
    private val shapes = listOf(
        SegmentShape(SegmentKind.Corner, bend1.start, bend1.endInclusive),
        SegmentShape(SegmentKind.Straight, bend1.endInclusive, bend2.start),
        SegmentShape(SegmentKind.Corner, bend2.start, bend2.endInclusive),
        SegmentShape(SegmentKind.Straight, bend2.endInclusive, bend1.start),
    )
    private val detected = TrackRevision.first(
        circuit.frame,
        (0 until (circuit.length / 2).toInt()).map { circuit.xy(it * 2.0) },
        closed = true,
        shapes,
    )

    private val writers = 8
    private val rounds = 25

    /** Runs [write] on [writers] threads at once, each with its own store on [dir]; returns their results. */
    private fun <T> together(dir: File, write: (TrackStore, Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(writers)
        try {
            val barrier = CyclicBarrier(writers)
            val futures = (0 until writers).map { i ->
                val store = TrackStore(dir)
                pool.submit<T> {
                    barrier.await(10, TimeUnit.SECONDS)
                    write(store, i)
                }
            }
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun readable(dir: File, id: String): Track {
        val file = File(dir, "$id.track.json")
        assertTrue(file.isFile)
        val track = TrackJson.decode(file.readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") || it.name.contains(".unreadable-") })
        return track
    }

    @Test
    fun `parallel saves from separate stores never lose one`() {
        val dir = Files.createTempDirectory("tracks").toFile()
        val first = TrackStore(dir).save(circuit.track(), nowUtcMillis = 1_000)
        var revision = first.revision
        repeat(rounds) { round ->
            val saved = together(dir) { store, i -> store.save(first.copy(name = "Edit $round.$i")) }
            // Each save is the next revision after the one before it: none read a revision another had replaced.
            val revisions = saved.map { it.revision }.sorted()
            assertEquals((revision + 1..revision + writers).toList(), revisions, "round $round")
            revision += writers
            val stored = readable(dir, first.id)
            assertEquals(revision, stored.revision)
            assertEquals(saved.single { it.revision == revision }.name, stored.name)
        }
    }

    @Test
    fun `competing corrections of one identity are applied one after another`() {
        val dir = Files.createTempDirectory("tracks").toFile()
        val id = TrackStore(dir).save(circuit.track(), nowUtcMillis = 1_000).id
        assertNotNull(TrackStore(dir).updateIdentity(id, null, detected))
        var expected = detected
        repeat(rounds) { round ->
            val names = (0 until writers).map { "Corner $round.$it" }
            val after = together(dir) { store, i ->
                assertNotNull(store.correctIdentity(id, expected, SegmentEdit.Rename("c1", names[i]))).identity
            }
            // Exactly one writer's rename is made over the identity all of them saw; the others see it and stop.
            val made = names.map { assertNotNull(expected.corrected(SegmentEdit.Rename("c1", it))) }
            val stored = assertNotNull(readable(dir, id).identity)
            assertEquals(1, made.count { it == stored }, "round $round")
            assertTrue(after.all { it == stored }, "round $round")
            expected = stored
        }
    }

    @Test
    fun `saves, identity updates and corrections at once keep every change`() {
        val dir = Files.createTempDirectory("tracks").toFile()
        val first = TrackStore(dir).save(circuit.track(), nowUtcMillis = 1_000)
        assertNotNull(TrackStore(dir).updateIdentity(first.id, null, detected))
        val renamed = assertNotNull(detected.corrected(SegmentEdit.Rename("c1", "Turn One")))
        together(dir) { store, i ->
            when (i % 2) {
                0 -> store.save(first.copy(name = "Edit $i"))
                else -> store.correctIdentity(first.id, detected, SegmentEdit.Rename("c1", "Turn One"))
            }
        }
        val stored = readable(dir, first.id)
        // Every save counted, and the correction is kept: a save never writes back the identity it read before it.
        assertEquals(first.revision + writers / 2, stored.revision)
        assertEquals(renamed, stored.identity)
    }

    @Test
    fun `a reader setting a damaged file aside never takes the good track a writer just saved`() {
        val dir = Files.createTempDirectory("tracks").toFile()
        val first = TrackStore(dir).save(circuit.track(), nowUtcMillis = 1_000)
        val file = File(dir, "${first.id}.track.json")
        repeat(rounds) { round ->
            file.writeText("{ half a track")
            together(dir) { store, i ->
                if (i == 0) store.save(first.copy(name = "Good $round")) else store.get(first.id)
            }
            // Only the damaged text is ever set aside; the saved track is where it belongs.
            val aside = dir.listFiles { f -> f.name.contains(".unreadable-") }.orEmpty()
            assertTrue(aside.all { it.readText() == "{ half a track" }, "round $round")
            aside.forEach { it.delete() }
            assertEquals("Good $round", TrackJson.decode(file.readText()).name)
        }
    }
}
