@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.io.PosixFiles
import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.VboExport
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.usleep
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Simulated minutes per session; 25 min is one track session. Lower it if the simulator run gets too slow. */
private const val SessionMinutes = 25
private const val Sessions = 3
private const val MicrosPerSecond = 1_000_000L
private const val GnssHz = 25
private const val CanHz = 45
private const val RpmHz = 90
private const val TyreChannels = 8

/** Simulated second at which the disk fills up in the full-disk test. */
private const val FullAtSecond = 300

/** Records waiting for the writer, at most, before the fake sources wait: far below the 100 000 queue. */
private const val MaxBacklog = 20_000

/**
 * A track day on the iOS recorder (Moto Park readiness, 2026-11-03): three 25-minute sessions with
 * every source a car carries (RaceBox 25 Hz, FR-S CAN steering and brake ~45 Hz, rpm ~90 Hz, OBD
 * temperatures 0.5 Hz, eight tyre channels at 1 Hz, heart rate 1 Hz), a GNSS drop of 3 s and an OBD
 * drop of 10 s in each, then a session that runs out of storage. Each session must read back with
 * every submitted value, drop nothing, and export a VBO with one row per fix and time only going forward.
 */
class TrackDaySoakTest {
    private val root = NSTemporaryDirectory() + "soak-test-" + NSUUID().UUIDString
    private val store = SessionStore(root)

    private val steering = ChannelDef(820, "can_steering", "deg", "can", 1)
    private val brake = ChannelDef(821, "can_brake", "bar", "can", 1)
    private val rpm = ChannelDef(822, "can_rpm", "rpm", "can", 0)
    private val coolant = ChannelDef(803, "obd_coolant", "C", "obd", 0)
    private val oil = ChannelDef(806, "obd_oil", "C", "obd", 0)
    private val tyres = (0 until TyreChannels).map { ChannelDef(900 + it, "tpms_$it", "bar", "tpms", 2) }
    private val heart = ChannelDef(400, "hr_bpm", "bpm", "heartrate", 0)
    private val extra = listOf(steering, brake, rpm, coolant, oil) + tyres + heart

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    /** What one session handed to the recorder, per channel. */
    private class Sent {
        val values = HashMap<Int, Int>()
        var sets = 0
        var fixes = 0

        fun add(set: SampleSet) {
            sets++
            set.values.forEach { values[it.channelId] = (values[it.channelId] ?: 0) + 1 }
        }
    }

    @Test
    fun aTrackDayOfThreeSessionsLosesNothing() {
        repeat(Sessions) { s ->
            val gnss = FakeGnss(startUtcMillis = FakeGnss().startUtcMillis + s * 3_600_000L)
            val rec = Recorder(store).start(info(gnss))
            extra.forEach(rec::defineChannel)
            val seconds = SessionMinutes * 60
            val sent = Sent()
            for (sec in 0 until seconds) {
                second(rec, gnss, sec, seconds, sent)
                pace(rec)
            }
            assertTrue(rec.stop(), "session $s did not stop cleanly")
            assertEquals("user", rec.endReason)
            assertTrue(rec.writerText.endsWith("dropped=0"), "session $s: ${rec.writerText}")
            check(s, store.read(rec.id), sent)
        }
        assertEquals(List(Sessions) { SessionState.FINISHED }, store.list().map { it.state })
    }

    private fun info(gnss: FakeGnss) = SessionInfo(
        gnss.startUtcMillis,
        car = "FR-S",
        track = "Fake ring",
        devices = listOf("fake-gnss", "fake-can", "fake-obd", "fake-tpms", "fake-hr"),
        startFinish = gnss.startFinish,
        channels = GnssChannels.ALL,
    )

    /**
     * One second of data from every source. GNSS drops for 3 s at half time, OBD for 10 s at a third:
     * a dropped source submits nothing, as when its link is gone, and comes back on its own.
     */
    private fun second(rec: Recording, gnss: FakeGnss, sec: Int, seconds: Int, sent: Sent) {
        val t0 = sec * MicrosPerSecond
        fun submit(set: SampleSet) {
            rec.submit(set)
            sent.add(set)
        }
        if (sec !in seconds / 2 until seconds / 2 + 3) {
            repeat(GnssHz) {
                val n = sec.toLong() * GnssHz + it
                submit(SampleSet(n * MicrosPerSecond / GnssHz, gnss.fix(n)))
                sent.fixes++
            }
        }
        repeat(CanHz) {
            val v = listOf(ChannelValue(steering.id, (it % 90).toDouble()), ChannelValue(brake.id, it * 0.1))
            submit(SampleSet(t0 + it * MicrosPerSecond / CanHz + 7, v))
        }
        repeat(RpmHz) {
            submit(SampleSet(t0 + it * MicrosPerSecond / RpmHz + 3, listOf(ChannelValue(rpm.id, 3000.0 + it))))
        }
        if (sec % 2 == 0 && sec !in seconds / 3 until seconds / 3 + 10) {
            submit(SampleSet(t0 + 500, listOf(ChannelValue(coolant.id, 90.0), ChannelValue(oil.id, 100.0))))
        }
        tyres.forEachIndexed { w, def ->
            submit(SampleSet(t0 + 100_000 + w * 1_000L, listOf(ChannelValue(def.id, 2.1))))
        }
        submit(SampleSet(t0 + 900_000, listOf(ChannelValue(heart.id, 120.0 + sec % 40))))
    }

    /** Lets the writer catch up when it falls behind, as real sources only produce in real time. */
    private fun pace(rec: Recording) {
        while (rec.queuedForTest > MaxBacklog) usleep(1_000u)
    }

    /** Every submitted value is on disk, nothing was dropped, and the VBO has one forward row per fix. */
    private fun check(s: Int, data: SessionData, sent: Sent) {
        assertEquals("user", data.end?.reason)
        assertEquals(0L, data.truncatedBytes + data.skippedBytes, "session $s: torn or skipped bytes")
        assertEquals(sent.sets, data.sampleSets.size, "session $s: sample sets")
        val got = HashMap<Int, Int>()
        data.sampleSets.forEach { set -> set.values.forEach { got[it.channelId] = (got[it.channelId] ?: 0) + 1 } }
        assertEquals(sent.values, got, "session $s: values per channel")
        assertTrue(data.events.none { it.kind == EventKinds.DROPPED_SAMPLES }, "session $s: dropped samples")
        assertEquals("0", finished(data.id)["droppedSamples"], "session $s: droppedSamples")

        val vbo = StringBuilder()
        val written = VboExport.export(data, vbo)
        assertEquals(VboExport.Written(sent.fixes, sent.fixes, 0), written, "session $s: VBO")
        assertForward(s, vbo.toString())
    }

    private fun finished(id: String): Map<String, String> {
        val dir = store.list().single { it.id == id }.dir
        return parseProperties(PosixFiles.readBytes(PosixFiles.join(dir, SessionFileNames.FINISHED)))
    }

    /** The VBO time column (hhmmss.sss) only goes forward. */
    private fun assertForward(s: Int, text: String) {
        val lines = text.split("\r\n")
        val columns = lines[lines.indexOf("[column names]") + 1].split(" ")
        val at = columns.indexOf("time")
        assertTrue(at >= 0, "session $s: no time column in $columns")
        var last = -1.0
        for (line in lines.drop(lines.indexOf("[data]") + 1)) {
            if (line.isEmpty()) continue
            val time = line.split(" ")[at]
            val sec = time.substring(0, 2).toInt() * 3_600 + time.substring(2, 4).toInt() * 60 +
                time.substring(4).toDouble()
            assertTrue(sec > last, "session $s: time $time after ${last}s of day")
            last = sec
        }
    }

    /**
     * The disk fills up mid-session while every source keeps going. The session must end with
     * storage_full, finished and whole, keep everything that was written before the space ran out,
     * and say how much it could not write.
     */
    @Test
    fun aFullDiskEndsTheSessionWithEverythingBeforeKept() {
        val config = RecorderConfig(
            syncIntervalMs = 50,
            storageCheckIntervalMs = 10,
            storageWarnBytes = 1_000,
            storageStopBytes = 100,
        )
        val gnss = FakeGnss()
        val rec = Recorder(store, config).start(info(gnss))
        var free = 1L shl 40
        rec.freeBytesForTest = { free }
        var low = 0L
        rec.onStorageLow = { low = it }
        extra.forEach(rec::defineChannel)
        val seconds = SessionMinutes * 60
        val sent = Sent()
        for (sec in 0 until FullAtSecond) {
            second(rec, gnss, sec, seconds, sent)
            pace(rec)
        }
        rec.awaitWritten()
        val before = HashMap(sent.values)
        free = 50
        var sec = FullAtSecond
        while (rec.isRecording && sec < seconds) second(rec, gnss, sec++, seconds, sent)
        rec.awaitWritten()
        assertEquals("storage_full", rec.endReason, "still recording after ${sec - FullAtSecond} s of a full disk")
        assertEquals(50L, low, "storage warning")
        assertFullDiskSession(rec, before, sent.sets)
    }

    private fun assertFullDiskSession(rec: Recording, before: Map<Int, Int>, submitted: Int) {
        val data = store.read(rec.id)
        assertEquals("storage_full", data.end?.reason)
        assertEquals(SessionState.FINISHED, store.list().single().state)
        assertEquals(0L, data.truncatedBytes + data.skippedBytes, "torn or skipped bytes")
        val got = HashMap<Int, Int>()
        data.sampleSets.forEach { set -> set.values.forEach { got[it.channelId] = (got[it.channelId] ?: 0) + 1 } }
        for ((id, count) in before) {
            val onDisk = got[id] ?: 0
            assertTrue(onDisk >= count, "channel $id: $onDisk on disk, $count written before the disk filled")
        }
        val dropped = finished(rec.id)["droppedSamples"]?.toLong() ?: -1L
        assertTrue(dropped >= 0 && data.sampleSets.size + dropped <= submitted, "dropped $dropped of $submitted")
        assertTrue(data.events.any { it.kind == EventKinds.STORAGE_LOW }, "no storage_low event")
        assertTrue(data.events.any { it.kind == EventKinds.STORAGE_FULL }, "no storage_full event")
        val fixes = data.sampleSets.count { set -> set.values.any { it.channelId == GnssChannels.UTC_MS.id } }
        assertEquals(fixes, VboExport.export(data, StringBuilder()).fixes)
    }
}
