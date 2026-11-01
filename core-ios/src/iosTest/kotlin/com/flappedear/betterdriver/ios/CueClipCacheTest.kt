@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CueClips
import com.flappedear.betterdriver.coach.Phrases
import com.flappedear.betterdriver.coach.PolishPhrases
import com.flappedear.betterdriver.recorder.log.IosLog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create

/**
 * The iPhone keeps its pre-rendered cue clips as Android does (FBBD-48): rendered once per voice,
 * reused from the cache after, and every cue is joined from them.
 */
class CueClipCacheTest {
    private val base = NSTemporaryDirectory() + "cue-clips-test-" + NSUUID().UUIDString

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
        IosLog.sink = null
    }

    /** What the app's text-to-speech renders for a key: a little silence, the word, a little silence. */
    private fun rendered(key: String): NSData {
        val s = ShortArray(400 + key.length * 40) { if (it in 100 until 300 + key.length * 40) 4_000 else 0 }
        return s.usePinned { NSData.create(bytes = it.addressOf(0), length = (s.size * 2).toULong()) }
    }

    private fun renderAll(cache: CueClipCache): Int {
        val keys = cache.begin()
        keys.forEach { assertTrue(cache.rendered(it, rendered(it), 22_050), it) }
        assertTrue(cache.finish())
        return keys.size
    }

    @Test
    fun clipsAreRenderedOnceAndReusedForTheSameVoice() {
        val logged = mutableListOf<String>()
        IosLog.sink = { tag, message -> logged += "$tag: $message" }
        val first = CueClipCache(base, CoachLanguage.English, "Daniel|1.0|1.0")
        assertFalse(first.ready)
        assertNull(first.phrase("Best lap, 1 05 point 4"), "said live until the clips are ready")
        assertEquals(CueClips.vocabulary.size, renderAll(first))
        assertTrue(first.ready)

        val again = CueClipCache(base, CoachLanguage.English, "Daniel|1.0|1.0")
        assertEquals(0, renderAll(again), "the same voice renders nothing")
        // Android's lines (FBBD-48), so one set of checks reads both logs.
        val n = CueClips.vocabulary.size
        assertTrue("CueClips: preparing $n cue clips (en)" in logged, logged.joinToString("\n"))
        fun ready(rendered: Int) =
            Regex("""CueClips: cue clips ready in \d+ ms: $n clips, $rendered rendered, the rest from cache""")
        assertTrue(logged.any { ready(n).matches(it) }, logged.joinToString("\n"))
        assertTrue(logged.any { ready(0).matches(it) }, logged.joinToString("\n"))

        val faster = CueClipCache(base, CoachLanguage.English, "Daniel|1.2|1.0")
        assertEquals(n, renderAll(faster), "another speed renders afresh")
        val folders = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$base/coach-clips", null)
        assertEquals(1, folders?.size, "old voices' clips go")
    }

    @Test
    fun everyCueTextIsPlayedFromClips() {
        for ((language, book) in listOf(CoachLanguage.English to Phrases, CoachLanguage.Polish to PolishPhrases)) {
            val cache = CueClipCache(base, language, "voice-${language.tag}")
            renderAll(cache)
            val texts = listOf(
                book.lapTime(92_400, best = true), book.lapTime(58_300, best = false), book.plan(listOf(1, 3, 5)),
            )
            for (text in texts) {
                val sound = assertNotNull(cache.phrase(text), text)
                assertEquals(22_050, sound.sampleRate)
                assertTrue(sound.samples.length.toInt() > 0 && sound.durationMillis > 0, text)
            }
        }
    }

    @Test
    fun aClipThatCannotBeRenderedLeavesTheLiveVoice() {
        val logged = mutableListOf<String>()
        IosLog.sink = { tag, message -> logged += "$tag: $message" }
        val cache = CueClipCache(base, CoachLanguage.English, "v")
        val keys = cache.begin()
        assertFalse(cache.rendered(keys.first(), NSData(), 22_050), "an empty render is not kept")
        keys.drop(1).forEach { cache.rendered(it, rendered(it), 22_050) }
        assertFalse(cache.finish())
        assertFalse(cache.ready)
        assertNull(cache.phrase("Best lap, 1 05 point 4"))
        val unavailable = "CueClips: pre-rendered voice unavailable, using live text-to-speech: 1 clips unreadable"
        assertTrue(unavailable in logged, logged.joinToString("\n"))
        // Next time only the missing clip is rendered.
        assertEquals(listOf(keys.first()), CueClipCache(base, CoachLanguage.English, "v").begin())
    }
}
