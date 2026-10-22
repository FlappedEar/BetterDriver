package com.flappedear.betterdriver.video.gpmf

import com.flappedear.betterdriver.video.mp4.Mp4FormatException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Damaged GPMF payloads (audit 2026-10-22, A07): every one ends with [Mp4FormatException] or parses,
 * in bounded time. Before the fix an empty TYPE made the reader loop forever.
 */
class GpmfCorruptTest {
    private val utc0 = 1_760_180_000_000L

    private fun hex(s: String): ByteArray =
        s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun ints(vararg v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { v.forEach(::writeInt) }
        return out.toByteArray()
    }

    private fun refuses(payload: ByteArray) = assertTimeoutPreemptively(Duration.ofSeconds(5)) {
        assertFailsWith<Mp4FormatException> { GpmfGps.parsePayload(payload, 0, 1_000_000) }
    }

    private fun parsesOrRefuses(payload: ByteArray) = assertTimeoutPreemptively(Duration.ofSeconds(5)) {
        try {
            GpmfGps.parsePayload(payload, 0, 1_000_000)
        } catch (_: Mp4FormatException) {
            // The contract: a damaged payload is this exception, nothing else.
        }
    }

    /** A STRM with this TYPE (raw bytes) and one GPS9 sample of [structSize] zero bytes. */
    private fun gps9WithType(type: ByteArray, structSize: Int = 32): ByteArray = FakeGoProFile.nested(
        "STRM",
        FakeGoProFile.klv("TYPE", 'c', 1, type.size, type),
        FakeGoProFile.klv("GPS9", '?', structSize, 1, ByteArray(structSize)),
    )

    @Test
    fun `audit A07 input - empty TYPE with GPS9 data ends with a format error`() {
        refuses(hex("53 54 52 4d 00 01 00 14 54 59 50 45 63 01 00 00 47 50 53 39 3f 01 00 01 00 00 00 00"))
    }

    @Test
    fun `NUL-only, unsupported and short TYPE end with a format error`() {
        refuses(gps9WithType(ByteArray(4)))
        refuses(gps9WithType("lllllllZZ".toByteArray()))
        refuses(gps9WithType("lllll".toByteArray(), 20))
        // TYPE describes 32 bytes, the structure says 30.
        refuses(gps9WithType("lllllllSS".toByteArray(), 30))
    }

    @Test
    fun `incomplete GPS5 and GPS9 data end with a format error`() {
        // repeat 2 but only one GPS5 structure of data: the size × repeat check stops it.
        val gps5 = FakeGoProFile.nested(
            "STRM",
            FakeGoProFile.klv("GPS5", 'l', 20, 1, ints(1, 2, 3, 4, 5)),
        ).also { it[it.size - 21] = 2 } // repeat low byte of GPS5: 1 -> 2
        refuses(gps5)
        // GPS5 whose structure is 16 bytes: four values per sample, five needed.
        refuses(FakeGoProFile.nested("STRM", FakeGoProFile.klv("GPS5", 'l', 16, 2, ints(1, 2, 3, 4, 5, 6, 7, 8))))
        // GPS9 with a date centuries away.
        val farDate = ints(0, 0, 0, 0, 0, 1_000_000, 0) + byteArrayOf(0, 1, 0, 3)
        refuses(
            FakeGoProFile.nested(
                "STRM",
                FakeGoProFile.klv("TYPE", 'c', 1, 9, "lllllllSS".toByteArray()),
                FakeGoProFile.klv("GPS9", '?', 32, 1, farDate),
            ),
        )
    }

    @Test
    fun `GPS9 without TYPE uses the default layout`() {
        val sample = ints(522_000_000, 191_000_000, 0, 10_000, 10_000, 9000, 0) + byteArrayOf(0, 1, 0, 3)
        val samples = GpmfGps.parsePayload(
            FakeGoProFile.nested("STRM", FakeGoProFile.klv("GPS9", '?', 32, 1, sample)),
            0,
            1_000_000,
        )
        assertEquals(1, samples.size)
        assertEquals(3, samples[0].fix)
    }

    @Test
    fun `nesting deeper than GoPro writes is refused before the stack runs out`() {
        var payload = FakeGoProFile.nested("STRM", FakeGoProFile.klv("STNM", 'c', 1, 3, "GPS".toByteArray()))
        repeat(20) { payload = FakeGoProFile.nested("DEVC", payload) }
        refuses(payload)
    }

    @Test
    fun `every single byte damage of a GPS5 and a GPS9 payload parses or refuses`() {
        for (good in listOf(
            FakeGoProFile.gps5Payload(utc0, List(18) { it.toDouble() }),
            FakeGoProFile.gps9Payload(utc0, 100, List(10) { 20.0 + it }),
        )) {
            for (at in good.indices) {
                for (value in listOf(0x00, 0xFF, 0x3F, 0x01)) {
                    parsesOrRefuses(good.copyOf().also { it[at] = value.toByte() })
                }
            }
        }
    }
}
