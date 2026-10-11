package com.flappedear.betterdriver.video.gpmf

import com.flappedear.betterdriver.video.mp4.Mp4Reader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GpmfGpsTest {
    @TempDir
    lateinit var dir: File

    private val utc0 = 1_760_180_000_000L // 2025-10-11T10:53:20Z

    @Test
    fun `GPS5 payload gives scaled samples spread over the payload time with GPSU`() {
        val payload = FakeGoProFile.gps5Payload(utc0, List(18) { it.toDouble() })
        val samples = GpmfGps.parsePayload(payload, startUs = 5_000_000, durationUs = 1_000_000)
        assertEquals(18, samples.size)
        assertEquals(52.2, samples[0].lat, 1e-7)
        assertEquals(19.1, samples[0].lon, 1e-7)
        assertEquals(17.0, samples[17].speedMps, 1e-9)
        assertEquals(5_000_000, samples[0].videoTimeUs)
        assertEquals(5_000_000 + 9 * 55_555, samples[9].videoTimeUs)
        assertEquals(utc0, samples[0].utcMillis)
        assertEquals(3, samples[0].fix)
    }

    @Test
    fun `GPS9 payload gives per-sample UTC`() {
        val payload = FakeGoProFile.gps9Payload(utc0, 100, List(10) { 20.0 + it })
        val samples = GpmfGps.parsePayload(payload, startUs = 0, durationUs = 1_000_000)
        assertEquals(10, samples.size)
        assertEquals(utc0 + 900, samples[9].utcMillis)
        assertEquals(29.0, samples[9].speedMps, 1e-9)
        assertEquals(52.2, samples[9].lat, 1e-7)
        assertEquals(3, samples[9].fix)
    }

    @Test
    fun `reads the telemetry track of an MP4 without touching other data`() {
        val file = File(dir, "GX010001.MP4")
        FakeGoProFile.writeMp4(file, List(3) { s -> FakeGoProFile.gps5Payload(utc0 + s * 1000, List(10) { 10.0 + s }) })
        val info = Mp4Reader.read(file)
        val track = assertNotNull(info.gpmfTrack)
        assertEquals("meta", track.handler)
        assertEquals(3, track.samples.size)
        assertEquals(2_000_000, track.samples[2].startUs)
        assertEquals(3_000_000, info.durationUs)
        assertNotNull(info.creationTimeMillis)

        val gps = GpmfGps.read(file)
        assertEquals(30, gps.size)
        assertEquals(2_000_000, gps[20].videoTimeUs)
        assertEquals(12.0, gps[20].speedMps, 1e-9)
        assertEquals(utc0 + 2000, gps[20].utcMillis)
    }

    @Test
    fun `a file without telemetry has no GPS`() {
        val file = File(dir, "phone.mp4")
        FakeGoProFile.writeMp4(file, emptyList())
        assertEquals(emptyList(), GpmfGps.read(file))
    }
}
