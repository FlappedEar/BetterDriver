@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CueClipLog
import com.flappedear.betterdriver.coach.CueClipSet
import com.flappedear.betterdriver.coach.CueClips
import com.flappedear.betterdriver.coach.Pcm
import com.flappedear.betterdriver.coach.PhraseBook
import com.flappedear.betterdriver.coach.Wav
import kotlin.concurrent.Volatile
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

/** A phrase joined from cue clips, for the app to play: mono 16-bit samples, native byte order. */
class CueClipSound(val sampleRate: Int, val samples: NSData, val durationMillis: Long)

/**
 * The pre-rendered cue clips on the iPhone (FBBD-48), as CueClipBank on Android: every word group and
 * number the coach can say in [language] ([CueClips.vocabulary]) is rendered once by the app's
 * text-to-speech ([begin] names the ones still to render, [rendered] keeps each), saved as WAV files
 * under [cacheDir], one folder per [voice] (its identifier, speed and pitch), and held in memory, so a
 * phrase is joined from clips ([phrase]) and plays at once. Clips already rendered with the same voice
 * are reused, so only the first coached session after an install or a change of voice renders.
 * Until [finish] has loaded them, or if rendering failed, [phrase] gives null and the live voice speaks.
 * Rendering calls come from one thread; [phrase] from any.
 */
class CueClipCache(cacheDir: String, private val language: CoachLanguage, voice: String) {
    private val root = cacheDir.trimEnd('/') + "/" + ClipsDir
    private val dir = root + "/" + CueClipSet.folderName(voice, language, ClipFormat)
    private val vocabulary = CueClips.vocabulary(PhraseBook.of(language))
    private var startedAt = 0.0
    private var renderedCount = 0

    @Volatile
    private var set: CueClipSet? = null

    @Volatile
    private var failed = false

    /** Whether the clips are loaded, so phrases are said from them. */
    val ready: Boolean get() = set != null

    /**
     * Starts preparing the clips: logs it as Android does, removes the clips of other voices, and gives
     * the words and numbers still to render, in order; empty when all are already rendered.
     */
    fun begin(): List<String> {
        startedAt = NSDate().timeIntervalSince1970
        CueClipLog.preparing(language)
        val files = NSFileManager.defaultManager
        files.contentsOfDirectoryAtPath(root, null)?.filterIsInstance<String>()?.forEach { name ->
            if (root + "/" + name != dir) files.removeItemAtPath(root + "/" + name, null)
        }
        files.createDirectoryAtPath(dir, true, null, null)
        val missing = vocabulary.filterNot { files.fileExistsAtPath(path(it)) }
        renderedCount = missing.size
        return missing
    }

    /**
     * Keeps [key]'s clip, rendered as [samples] (mono 16-bit, native byte order) at [sampleRate];
     * false if it could not be saved or is empty.
     */
    fun rendered(key: String, samples: NSData, sampleRate: Int): Boolean {
        val n = samples.length.toInt() / 2
        if (n == 0 || sampleRate <= 0 || key !in vocabulary) return false
        val pcm = ShortArray(n)
        pcm.usePinned { memcpy(it.addressOf(0), samples.bytes, (n * 2).toULong()) }
        return Wav.write(Pcm(sampleRate, pcm)).toNSData().writeToFile(path(key), atomically = true)
    }

    /** Loads every clip once all are rendered; false, and logged, when one is missing or damaged. */
    fun finish(): Boolean {
        val loaded = CueClipSet.load(
            language,
            read = { key -> NSData.dataWithContentsOfFile(path(key))?.toBytes() },
            delete = { key -> NSFileManager.defaultManager.removeItemAtPath(path(key), null) },
        )
        val s = loaded.set ?: run {
            fail("${loaded.bad} clips unreadable")
            return false
        }
        set = s
        val ms = ((NSDate().timeIntervalSince1970 - startedAt) * MillisPerSecond).toLong()
        CueClipLog.ready(ms, s.size, renderedCount)
        return true
    }

    /** Rendering failed: logged once; the coach speaks live. */
    fun fail(why: String) {
        if (set != null || failed) return
        failed = true
        CueClipLog.unavailable(why)
    }

    /** [text] joined from clips, or null when they are not ready or one of its parts has none. */
    fun phrase(text: String): CueClipSound? {
        val pcm = set?.phrase(text, language) ?: return null
        val bytes = (pcm.samples.size * 2).toULong()
        val data = pcm.samples.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes) }
        return CueClipSound(pcm.sampleRate, data, pcm.durationMillis)
    }

    private fun path(key: String) = dir + "/" + CueClips.fileName(key)

    private companion object {
        const val ClipsDir = "coach-clips"

        /** Bumped when the clips' format or rendering changes, so old ones are rendered again. */
        const val ClipFormat = 1
        const val MillisPerSecond = 1_000.0
    }
}

private fun NSData.toBytes(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
