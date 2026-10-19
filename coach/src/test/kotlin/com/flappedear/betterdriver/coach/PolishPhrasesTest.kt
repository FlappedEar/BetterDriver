@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolishPhrasesTest {
    private val corner = Corner(3, 0.0, 0.0, 0.0, 0.0, Turn.Left)
    private val pl = PolishPhrases

    @Test
    fun `corner cues say the target first, then the change`() {
        assertEquals("Zakręt 3, hamuj na 100, 10 później", pl.corner(corner, Advice.BrakeLater(10, at = 100)))
        assertEquals("Zakręt 3, hamuj na 120, 10 wcześniej", pl.corner(corner, Advice.BrakeEarlier(10, at = 120)))
        assertEquals("Zakręt 3, 60 w apeksie, 5 więcej", pl.corner(corner, Advice.CarrySpeed(5, goalKmh = 60)))
        assertEquals("Zakręt 3, hamuj trochę później", pl.corner(corner, Advice.BrakeLater(10, precise = false)))
        assertEquals("Zakręt 3, trochę więcej prędkości", pl.corner(corner, Advice.CarrySpeed(5, precise = false)))
        assertEquals("Zakręt 3, hamuj wcześniej", pl.corner(corner, Advice.BrakeEarlier()))
        assertEquals("Zakręt 3, hamuj 15 metrów później", pl.corner(corner, Advice.BrakeLater(15)))
        assertEquals("Zakręt 3, 5 więcej", pl.corner(corner, Advice.CarrySpeed(5)))
    }

    @Test
    fun `metres agree with the number`() {
        assertEquals("metr", pl.metres(1))
        assertEquals(listOf("metry", "metry", "metry"), listOf(2, 3, 4).map(pl::metres))
        assertEquals(listOf("metrów", "metrów", "metrów", "metrów"), listOf(5, 12, 14, 25).map(pl::metres))
        assertEquals(listOf("metry", "metry"), listOf(22, 104).map(pl::metres))
    }

    @Test
    fun `short cue, coach line and praise`() {
        val advice = Advice.BrakeLater(10, at = 100)
        val cue = Cue(1, 0, 5, CueKind.Corner, pl.corner(corner, advice), 3, advice, turn = 3)
        assertEquals("Zakręt 3, hamuj na 100", pl.shortCorner(cue))
        assertEquals("Hamuj na 100 m · 10 później", pl.line(advice))
        assertEquals("60 km/h w apeksie · 5 więcej", pl.line(Advice.CarrySpeed(5, goalKmh = 60)))
        assertNull(pl.line(Advice.BrakeLater(10, precise = false)))
        assertEquals("Zakręt 3, to zadziałało", pl.praise(corner, Praise.Worked))
        assertEquals("Zakręt 3, najlepiej dotąd", pl.praise(corner, Praise.BestYet))
    }

    @Test
    fun `lap times use przecinek`() {
        assertEquals("1 32 przecinek 4", pl.lapTime(92_449, best = false))
        assertEquals("Najlepsze okrążenie, 2 05 przecinek 0", pl.lapTime(124_960, best = true))
        assertEquals("58 przecinek 3", pl.lapTime(58_300, best = false))
    }

    @Test
    fun `numbers over 99 are said as hundreds and the rest`() {
        assertEquals(listOf("100", "50"), pl.numberKeys("150"))
        assertEquals(listOf("100", "5"), pl.numberKeys("105"))
        assertEquals(listOf("200"), pl.numberKeys("200"))
        assertEquals(listOf("900", "99"), pl.numberKeys("999"))
        assertEquals(listOf("60"), pl.numberKeys("60"))
        assertEquals(listOf("05"), pl.numberKeys("05"))
        assertEquals(listOf("1500"), pl.numberKeys("1500"))
    }

    @Test
    fun `every Polish phrase has clips`() {
        for (at in 5..995 step 5) {
            assertTrue(CueClips.covers(pl.corner(corner, Advice.BrakeLater(10, at = at)), pl), "at $at")
            assertTrue(CueClips.covers(pl.corner(corner, Advice.BrakeEarlier(15, at = at)), pl), "at $at")
        }
        for (kmh in 20..300) assertTrue(CueClips.covers(pl.corner(corner, Advice.CarrySpeed(5, goalKmh = kmh)), pl))
        for (m in 1..60) assertTrue(CueClips.covers(pl.corner(corner, Advice.BrakeLater(m)), pl), "$m m")
        for (ms in listOf(58_300L, 65_400L, 92_449L, 124_960L, 600_000L)) {
            assertTrue(CueClips.covers(pl.lapTime(ms, best = true), pl), pl.lapTime(ms, best = true))
        }
        assertTrue(CueClips.covers(pl.praise(corner, Praise.Worked), pl))
        assertTrue(CueClips.covers("Zakręt 12, najlepiej dotąd", pl))
        assertFalse(CueClips.covers("Zakręt 3, hamuj na 1500, 10 później", pl))
        // English clips do not say Polish phrases.
        assertFalse(CueClips.covers(pl.corner(corner, Advice.BrakeLater(10, at = 100))))
    }

    @Test
    fun `Polish clips are their own, English ones unchanged`() {
        val polish = CueClips.vocabulary(pl)
        assertTrue("Zakręt" in polish && "200" in polish && "hundred" !in polish, polish.toString())
        assertEquals(CueClips.vocabulary, CueClips.vocabulary(Phrases))
        assertTrue("hundred" in CueClips.vocabulary && "oh" in CueClips.vocabulary)
        // Distinct file names, so clips never overwrite each other.
        assertEquals(polish.size, polish.map(CueClips::fileName).toSet().size)
    }

    @Test
    fun `a whole hundred is one word long`() {
        val one = pl.durationMicros("Zakręt 3, hamuj na 200")
        val two = pl.durationMicros("Zakręt 3, hamuj na 250")
        assertEquals(400_000L, two - one)
        assertEquals(PhraseBook.of(CoachLanguage.Polish), pl)
        assertEquals(PhraseBook.of(CoachLanguage.English), Phrases)
    }

    @Test
    fun `the language is stored with the other settings`() {
        val f = kotlin.io.path.createTempFile("coach", ".properties").toFile()
        try {
            val store = CoachSettingsStore(f)
            assertEquals(CoachLanguage.English, store.read().language)
            store.write(CoachSettings(language = CoachLanguage.Polish))
            assertEquals(CoachLanguage.Polish, store.read().language)
        } finally {
            f.delete()
        }
    }
}
