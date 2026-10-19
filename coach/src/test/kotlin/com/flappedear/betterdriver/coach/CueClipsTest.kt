@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueClipsTest {
    private val gap = CueClips.WordGapMillis
    private val comma = CueClips.CommaPauseMillis

    @Test
    fun `a corner cue splits into its words and numbers`() {
        assertEquals(
            listOf(ClipPart("Turn", gap), ClipPart("3", comma), ClipPart("brake", gap), ClipPart("15", gap),
                ClipPart("metres later", 0)),
            CueClips.parts("Turn 3, brake 15 metres later"),
        )
        assertEquals(
            listOf(ClipPart("Turn", gap), ClipPart("12", comma), ClipPart("brake earlier", 0)),
            CueClips.parts("Turn 12, brake earlier"),
        )
    }

    @Test
    fun `a lap time splits into its numbers`() {
        assertEquals(
            listOf(ClipPart("Best lap", comma), ClipPart("1", gap), ClipPart("05", gap), ClipPart("point", gap),
                ClipPart("4", 0)),
            CueClips.parts(Phrases.lapTime(65_400, best = true)),
        )
    }

    @Test
    fun `numbers over 99 are said as track people say them`() {
        fun keys(text: String) = CueClips.parts(text).map { it.key }
        assertEquals(listOf("Turn", "3", "brake at", "1", "50", "10", "later"), keys("Turn 3, brake at 150, 10 later"))
        assertEquals(listOf("brake at", "1", "oh", "5"), keys("brake at 105"))
        assertEquals(listOf("brake at", "2", "hundred"), keys("brake at 200"))
        assertEquals(listOf("1", "02", "point", "5"), keys("1 02 point 5"), "lap times keep their padded seconds")
    }

    @Test
    fun `every phrase the coach can say is covered by the vocabulary`() {
        for (n in 1..40) {
            val corner = Corner(n, 0.0, 0.0, 0.0, 0.0, Turn.Left)
            for (m in 5..MaxBrakeLaterMeters.toInt() step 5) {
                assertTrue(CueClips.covers(Phrases.corner(corner, Advice.BrakeLater(m))))
            }
            for (k in 1..MaxCarryKmh.toInt()) {
                assertTrue(CueClips.covers(Phrases.corner(corner, Advice.CarrySpeed(k))))
            }
            assertTrue(CueClips.covers(Phrases.corner(corner, Advice.BrakeEarlier())))
            // Below High confidence, without the number (FBBD-177).
            assertTrue(CueClips.covers(Phrases.corner(corner, Advice.BrakeLater(10, precise = false))))
            assertTrue(CueClips.covers(Phrases.corner(corner, Advice.CarrySpeed(4, precise = false))))
        }
        for (ms in 20_000L..600_000L step 3_700L) {
            assertTrue(CueClips.covers(Phrases.lapTime(ms, best = false)), Phrases.lapTime(ms, best = false))
            assertTrue(CueClips.covers(Phrases.lapTime(ms, best = true)))
        }
        // With targets (FBBD-242): brake points and apex speeds up to 999 are joined from clips.
        for (n in 1..40) {
            val corner = Corner(n, 0.0, 0.0, 0.0, 0.0, Turn.Left)
            for (at in 5..400 step 5) {
                assertTrue(CueClips.covers(Phrases.corner(corner, Advice.BrakeLater(10, at = at))), "at $at")
                assertTrue(CueClips.covers(Phrases.corner(corner, Advice.BrakeEarlier(15, at = at))), "at $at")
            }
            for (kmh in 20..260) {
                assertTrue(CueClips.covers(Phrases.corner(corner, Advice.CarrySpeed(5, goalKmh = kmh))), "$kmh km/h")
            }
        }
        assertFalse(CueClips.covers("Turn 3, brake at 1500, 10 later"), "a number beyond the clips stays live")
        assertFalse(CueClips.covers("Box this lap"))
        assertFalse(CueClips.covers(""))
    }

    @Test
    fun `clip file names are distinct`() {
        val names = CueClips.vocabulary.map(CueClips::fileName)
        assertEquals(names.size, names.toSet().size)
        assertTrue("metres_later.wav" in names)
        assertTrue("05.wav" in names && "5.wav" in names)
    }

    @Test
    fun `a wav round trips and stereo mixes down`() {
        val pcm = Pcm(22_050, shortArrayOf(0, 1_000, -1_000, 32_000))
        val back = assertNotNull(Wav.read(Wav.write(pcm)))
        assertEquals(22_050, back.sampleRate)
        assertContentEquals(pcm.samples, back.samples)

        val stereo = Wav.write(Pcm(8_000, shortArrayOf(100, 300, -200, 0))).also {
            it[22] = 2 // channels
        }
        assertContentEquals(shortArrayOf(200, -100), assertNotNull(Wav.read(stereo)).samples)

        assertNull(Wav.read(ByteArray(10)))
        assertNull(Wav.read("RIFF....WAVEjunk".toByteArray() + ByteArray(40)))
    }

    @Test
    fun `a wav whose writer left the data size unset reads to the end`() {
        val bytes = Wav.write(Pcm(16_000, shortArrayOf(1, 2, 3)))
        for (i in 40..43) bytes[i] = 0
        assertContentEquals(shortArrayOf(1, 2, 3), assertNotNull(Wav.read(bytes)).samples)
    }

    @Test
    fun `a wav with a huge chunk size reads to the end or as damaged, never throws`() {
        val huge = byteArrayOf(0xF0.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        val data = Wav.write(Pcm(16_000, shortArrayOf(1, 2, 3))).also { huge.copyInto(it, 40) }
        assertContentEquals(shortArrayOf(1, 2, 3), assertNotNull(Wav.read(data)).samples)
        val fmt = Wav.write(Pcm(16_000, shortArrayOf(1, 2, 3))).also { huge.copyInto(it, 16) }
        assertNull(Wav.read(fmt))
    }

    @Test
    fun `trimming keeps the word and a little padding`() {
        val rate = 1_000 // a sample per millisecond
        val s = ShortArray(200).also { for (i in 80 until 120) it[i] = 5_000 }
        val t = Pcm(rate, s).trimmed(padMillis = 10)
        assertEquals(60, t.samples.size)
        assertEquals(0, Pcm(rate, ShortArray(50)).trimmed().samples.size)
    }

    @Test
    fun `joining clips adds each pause`() {
        val a = Pcm(1_000, ShortArray(10) { 1 })
        val b = Pcm(1_000, ShortArray(5) { 2 })
        val j = assertNotNull(Pcm.join(listOf(a to 20, b to 0)))
        assertEquals(35, j.samples.size)
        assertEquals(1, j.samples[9].toInt())
        assertEquals(0, j.samples[10].toInt())
        assertEquals(2, j.samples[30].toInt())
        assertNull(Pcm.join(listOf(a to 0, Pcm(2_000, ShortArray(1)) to 0)), "rates differ")
        assertNull(Pcm.join(emptyList()))
    }

}
