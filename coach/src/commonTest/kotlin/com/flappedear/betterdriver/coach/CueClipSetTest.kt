@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.steering.SteerAdvice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pre-rendered cue clips both apps load (FBBD-48): with a clip for every word and number of the
 * vocabulary, every cue the coach can say in either language is joined from clips.
 */
class CueClipSetTest {
    private val rate = 16_000

    /** A clip per key: 20 ms of silence, the "word" (its own length, never silent), 20 ms of silence. */
    private fun files(language: CoachLanguage): MutableMap<String, ByteArray> =
        CueClips.vocabulary(PhraseBook.of(language)).associateWith { key ->
            val word = ShortArray(rate / 100 + key.length * 10) { 3_000 }
            Wav.write(Pcm(rate, ShortArray(320) + word + ShortArray(320)))
        }.toMutableMap()

    private fun load(language: CoachLanguage, files: MutableMap<String, ByteArray>) =
        CueClipSet.load(language, read = { files[it] }, delete = { files.remove(it) })

    /** Every kind of phrase the coach says, as CueClips.vocabulary samples them, over many numbers. */
    private fun cueTexts(book: PhraseBook): List<String> {
        val out = mutableListOf<String>()
        for (n in 1..25) {
            val c = Corner(n, 0.0, 0.0, 0.0, 0.0, Turn.Left)
            for (m in listOf(1, 2, 5, 15, 40)) {
                out += book.corner(c, Advice.BrakeLater(m, at = m * 7))
                out += book.corner(c, Advice.BrakeEarlier(m, at = 100 + m))
                out += book.corner(c, Advice.CarrySpeed(m, goalKmh = 60 + m * 3))
                out += book.corner(c, Advice.Line(m), Advice.Line(-m))
                out += book.corner(c, Advice.Throttle(m, afterApex = m))
                out += book.corner(c, Advice.Steer(SteerAdvice.OpenEarlier(m)), Advice.Throttle(m))
            }
            out += book.corner(c, Advice.BrakeLater(10, precise = false))
            out += book.corner(c, Advice.Steer(SteerAdvice.Less), Advice.Steer(SteerAdvice.More))
            out += book.corner(c, Advice.Steer(SteerAdvice.Smoother), Advice.Steer(SteerAdvice.Oversteer))
            for (g in 1..6) out += book.corner(c, Advice.BrakeLater(10, at = 150), Advice.Gear(g))
            out += book.praise(c, Praise.Worked)
            out += book.praise(c, Praise.BestYet)
        }
        out += book.plan(listOf(3))
        out += book.plan(listOf(1, 4, 7))
        for (ms in 40_000L..200_000L step 7_300L) {
            out += book.lapTime(ms, best = true)
            out += book.lapTime(ms, best = false)
        }
        return out
    }

    @Test
    fun everyCueIsJoinedFromClipsInBothLanguages() {
        for (language in CoachLanguage.entries) {
            val book = PhraseBook.of(language)
            val set = assertNotNull(load(language, files(language)).set, language.tag)
            assertEquals(CueClips.vocabulary(book).size, set.size)
            for (text in cueTexts(book)) {
                val parts = CueClips.parts(text, book)
                val pcm = assertNotNull(set.phrase(text, language), "${language.tag}: $text")
                // Each clip without its silence, but for 15 ms on each side, and the pauses between.
                val pad = 2 * pcm.frames(Pcm.TrimPadMillis)
                val expected = parts.sumOf { rate / 100 + it.key.length * 10 + pad + pcm.frames(it.pauseAfterMillis) }
                assertEquals(expected, pcm.samples.size, "${language.tag}: $text")
            }
        }
    }

    @Test
    fun aPhraseOfAnotherLanguageOrWithoutClipsIsSaidLive() {
        val set = assertNotNull(load(CoachLanguage.English, files(CoachLanguage.English)).set)
        assertNull(set.phrase(Phrases.lapTime(65_400, best = true), CoachLanguage.Polish))
        assertNull(set.phrase("Box this lap"))
        assertNull(set.phrase(""))
    }

    @Test
    fun aMissingOrSilentClipIsDeletedAndNoSetIsLoaded() {
        val f = files(CoachLanguage.English)
        f.remove("brake at")
        f["point"] = Wav.write(Pcm(rate, ShortArray(800)))
        f["later"] = ByteArray(12)
        val loaded = load(CoachLanguage.English, f)
        assertNull(loaded.set)
        assertEquals(3, loaded.bad)
        assertTrue("point" !in f && "later" !in f, "damaged clips are rendered again next time")
    }

    @Test
    fun theClipFolderChangesWithTheVoiceTheLanguageOrTheFormat() {
        val en = CueClipSet.folderName("Daniel|1.0|1.0", CoachLanguage.English, 1)
        assertEquals(en, CueClipSet.folderName("Daniel|1.0|1.0", CoachLanguage.English, 1))
        assertNotEquals(en, CueClipSet.folderName("Daniel|1.1|1.0", CoachLanguage.English, 1))
        assertNotEquals(en, CueClipSet.folderName("Daniel|1.0|1.0", CoachLanguage.Polish, 1))
        assertNotEquals(en, CueClipSet.folderName("Daniel|1.0|1.0", CoachLanguage.English, 2))
        assertTrue(en.all { it.isLetterOrDigit() }, en)
    }

    @Test
    fun aWavWrittenInCommonCodeReadsBack() {
        val pcm = Pcm(22_050, shortArrayOf(0, 1_000, -1_000, 32_000, -32_768))
        val back = assertNotNull(Wav.read(Wav.write(pcm)))
        assertEquals(22_050, back.sampleRate)
        assertContentEquals(pcm.samples, back.samples)
    }
}
