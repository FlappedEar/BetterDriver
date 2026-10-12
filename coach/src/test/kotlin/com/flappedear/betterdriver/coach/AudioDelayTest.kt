@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioDelayTest {
    @Test
    fun `gaps between clicks are random within 1·5 to 4 s`() {
        val r = Random(7)
        val gaps = List(2_000) { AudioDelay.nextGapMillis(r) }
        assertTrue(gaps.all { it in 1_500L..4_000L })
        assertTrue(gaps.min() < 1_600 && gaps.max() > 3_900, "covers the whole range")
        assertTrue(gaps.toSet().size > 500, "not a rhythm")
    }

    @Test
    fun `stats discard an outlier and give mean and spread of the rest`() {
        val delays = listOf(240L, 250, 260, 245, 255, 250, 1_200, 248, 252, 250)
        val s = assertNotNull(AudioDelay.stats(delays))
        assertEquals(9, s.used)
        assertEquals(1, s.discarded)
        assertEquals(250.0, s.meanMillis, 0.01)
        assertEquals(5.68, s.spreadMillis, 0.01)
    }

    @Test
    fun `a tight run keeps taps within the minimum outlier distance`() {
        // Every tap 250 ms but one at 300: the median absolute deviation is 0, yet 50 ms off is kept.
        val delays = List(9) { 250L } + 300L
        val s = assertNotNull(AudioDelay.stats(delays))
        assertEquals(10, s.used)
        assertEquals(0, s.discarded)
        assertEquals(255.0, s.meanMillis, 0.01)
    }

    @Test
    fun `too few taps give no stats`() {
        assertNull(AudioDelay.stats(listOf(200L, 210, 220, 230)))
        assertNotNull(AudioDelay.stats(listOf(200L, 210, 220, 230, 240)))
    }

    @Test
    fun `the delay is the headset mean less the speaker mean, rounded and capped`() {
        val speaker = TapStats(meanMillis = 231.0, spreadMillis = 20.0, used = 20, discarded = 0)
        val headset = TapStats(meanMillis = 418.0, spreadMillis = 30.0, used = 18, discarded = 2)
        val d = AudioDelay.delay(headset, speaker)
        assertEquals(190, d.millis)
        assertFalse(d.capped)
        // sqrt(30²/18 + 20²/20) = 8.4
        assertEquals(8, d.spreadMillis)

        assertEquals(0, AudioDelay.delay(speaker, headset).millis, "never negative")
        val slow = headset.copy(meanMillis = 2_600.0)
        val capped = AudioDelay.delay(slow, speaker)
        assertEquals(CoachSettings.MaxAudioDelayMillis, capped.millis)
        assertTrue(capped.capped)
    }

    @Test
    fun `a run counts one tap per click within the reaction window`() {
        val run = TapRun(taps = 3)
        assertNull(run.onTap(100), "no click yet")
        run.onClick(1_000)
        assertNull(run.onTap(1_050), "too soon to be a reaction")
        assertEquals(300L, run.onTap(1_300))
        assertNull(run.onTap(1_400), "one tap per click")
        run.onClick(4_000)
        assertNull(run.onTap(5_500), "too late: not for this click")
        run.onClick(7_000)
        assertEquals(260L, run.onTap(7_260))
        assertFalse(run.done)
        run.onClick(10_000)
        assertEquals(240L, run.onTap(10_240))
        assertTrue(run.done)
        run.onClick(12_000)
        assertNull(run.onTap(12_250), "done")
        assertEquals(listOf(300L, 260L, 240L), run.delays)
        assertEquals(5, run.clicks)
    }

    @Test
    fun `a simulated headset run measures the delay`() {
        val r = Random(3)
        fun run(lagMillis: Long): List<Long> {
            val run = TapRun()
            var t = 0L
            while (!run.done) {
                t += AudioDelay.nextGapMillis(r)
                run.onClick(t)
                // Reaction 220 ± 30 ms; one tap in ten is a late miss that the outlier filter drops.
                val reaction = 220 + r.nextLong(-30, 31) + if (r.nextInt(10) == 0) 700 else 0
                run.onTap(t + lagMillis + reaction)
            }
            return run.delays
        }
        val speaker = assertNotNull(AudioDelay.stats(run(lagMillis = 20)))
        val headset = assertNotNull(AudioDelay.stats(run(lagMillis = 280)))
        val d = AudioDelay.delay(headset, speaker)
        assertTrue(d.millis in 230..290, "measured ${d.millis}")
    }
}
