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
        assertEquals("Trójka, hamuj na 100, 10 później", pl.corner(corner, Advice.BrakeLater(10, at = 100)))
        assertEquals("Trójka, hamuj na 120, 10 wcześniej", pl.corner(corner, Advice.BrakeEarlier(10, at = 120)))
        assertEquals("Trójka, 60 w apeksie, 5 więcej", pl.corner(corner, Advice.CarrySpeed(5, goalKmh = 60)))
        assertEquals("Trójka, hamuj trochę później", pl.corner(corner, Advice.BrakeLater(10, precise = false)))
        assertEquals("Trójka, trochę więcej prędkości", pl.corner(corner, Advice.CarrySpeed(5, precise = false)))
        assertEquals("Trójka, hamuj wcześniej", pl.corner(corner, Advice.BrakeEarlier()))
        assertEquals("Trójka, hamuj 15 metrów później", pl.corner(corner, Advice.BrakeLater(15)))
        assertEquals("Trójka, 5 więcej", pl.corner(corner, Advice.CarrySpeed(5)))
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
        assertEquals("Trójka, hamuj na 100", pl.shortCorner(cue))
        assertEquals("Hamuj na 100 m · 10 później", pl.line(advice))
        assertEquals("60 km/h w apeksie · 5 więcej", pl.line(Advice.CarrySpeed(5, goalKmh = 60)))
        assertNull(pl.line(Advice.BrakeLater(10, precise = false)))
        assertEquals("Trójka, to zadziałało", pl.praise(corner, Praise.Worked))
        assertEquals("Trójka, najlepiej dotąd", pl.praise(corner, Praise.BestYet))
    }

    @Test
    fun `corners up to 10 are named by their number nouns`() {
        fun named(n: Int) = pl.praise(Corner(n, 0.0, 0.0, 0.0, 0.0, Turn.Straight), Praise.Worked)
        assertEquals("Jedynka, to zadziałało", named(1))
        assertEquals("Ósemka, to zadziałało", named(8))
        assertEquals("Dziesiątka, to zadziałało", named(10))
        assertEquals("Zakręt 11, to zadziałało", named(11))
        (1..12).forEach { assertTrue(CueClips.covers(named(it), pl), named(it)) }
    }

    @Test
    fun `lap times are the bare numbers`() {
        assertEquals("1, 32, 4", pl.lapTime(92_449, best = false))
        assertEquals("Najlepsze okrążenie, 2, 05, 0", pl.lapTime(124_960, best = true))
        assertEquals("58, 3", pl.lapTime(58_300, best = false))
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
        assertFalse(CueClips.covers("Trójka, hamuj na 1500, 10 później", pl))
        // English clips do not say Polish phrases.
        assertFalse(CueClips.covers(pl.corner(corner, Advice.BrakeLater(10, at = 100))))
    }

    @Test
    fun `Polish clips are their own, English ones unchanged`() {
        val polish = CueClips.vocabulary(pl)
        assertTrue("Zakręt" in polish && "200" in polish && "hundred" !in polish, polish.toString())
        assertEquals(CueClips.vocabulary, CueClips.vocabulary(Phrases))
        assertTrue("hundred" in CueClips.vocabulary && "oh" in CueClips.vocabulary)
        // Distinct file names, so clips never overwrite each other; a corner's name and the gear of
        // the same word ("Dwójka", "dwójka", FBBD-326) are one word and share its clip.
        val words = polish.map { it.lowercase() }.toSet()
        assertEquals(words.size, polish.map(CueClips::fileName).toSet().size)
    }

    @Test
    fun `a whole hundred is one word long`() {
        val one = pl.durationMicros("Trójka, hamuj na 200")
        val two = pl.durationMicros("Trójka, hamuj na 210")
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
