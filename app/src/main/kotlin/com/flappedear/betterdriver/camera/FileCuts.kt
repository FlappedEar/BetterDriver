package com.flappedear.betterdriver.camera

/**
 * Where each encoded sample of a 2.7K recording goes, as [WideEncoder] cuts it into files (FBBD-248).
 *
 * A file starts at a video keyframe; the first frames before one are dropped. Once a file holds
 * [cutBytes], the encoder is asked for a keyframe, and the next one starts the next file, so files
 * follow each other frame for frame. Sound recorded before that keyframe still goes to the file that
 * just ended, which closes with the first sound after it (or, when sound has stalled, [closeAfterUs]
 * of video later), so neither track has a gap at the cut. Times are the encoder's, in microseconds.
 */
internal class FileCuts(private val cutBytes: Long, private val closeAfterUs: Long = DefaultCloseAfterUs) {
    /** What to do with a video sample. */
    enum class Video { Drop, Start, Cut, Write }

    /** What to do with a sound sample. */
    enum class Audio { Drop, Previous, Current }

    /**
     * A video sample's place: [action]; [askKeyframe] when the encoder should now be asked for a
     * keyframe; [closePrevious] when the file before the current one should be closed first.
     */
    data class VideoStep(val action: Video, val askKeyframe: Boolean = false, val closePrevious: Boolean = false)

    /** A sound sample's place: [action]; [closePrevious] when the file before should be closed first. */
    data class AudioStep(val action: Audio, val closePrevious: Boolean = false)

    private var started = false
    private var firstUs = 0L
    private var asked = false

    /** Where the current file starts, in encoder time; 0 before the first file. */
    var baseUs = 0L
        private set

    /** Bytes of the current file so far, both tracks. */
    var bytes = 0L
        private set

    /** Whether the file before the current one is still open for its last sound. */
    var previousOpen = false
        private set

    /** Whether the current file is full enough that the next keyframe starts the next file. */
    val due: Boolean get() = started && bytes >= cutBytes

    /**
     * A video sample at [ptsUs] of [size] bytes, a keyframe when [key]. [nextReady]: whether the next
     * file is known, without which the current one goes on.
     */
    fun video(ptsUs: Long, size: Int, key: Boolean, nextReady: Boolean): VideoStep = when {
        !started && !key -> VideoStep(Video.Drop)
        !started -> {
            started = true
            firstUs = ptsUs
            begin(ptsUs, size, Video.Start, closePrevious = false)
        }
        cutsAt(ptsUs, key, nextReady) -> begin(ptsUs, size, Video.Cut, closePrevious = previousOpen)
        else -> write(ptsUs, size)
    }

    private fun cutsAt(ptsUs: Long, key: Boolean, nextReady: Boolean): Boolean {
        val newKeyframe = key && ptsUs > baseUs
        return due && nextReady && newKeyframe
    }

    private fun write(ptsUs: Long, size: Int): VideoStep {
        bytes += size
        val stalled = previousOpen && ptsUs - baseUs >= closeAfterUs
        if (stalled) previousOpen = false
        val ask = due && !asked
        if (ask) asked = true
        return VideoStep(Video.Write, askKeyframe = ask, closePrevious = stalled)
    }

    /** A sound sample at [ptsUs] of [size] bytes. */
    fun audio(ptsUs: Long, size: Int): AudioStep = when {
        !started || ptsUs < firstUs -> AudioStep(Audio.Drop)
        previousOpen && ptsUs < baseUs -> AudioStep(Audio.Previous)
        else -> {
            val close = previousOpen
            previousOpen = false
            bytes += size
            AudioStep(Audio.Current, closePrevious = close)
        }
    }

    /** The file before the current one was closed for another reason (no sound, or the end). */
    fun previousClosed() {
        previousOpen = false
    }

    private fun begin(ptsUs: Long, size: Int, action: Video, closePrevious: Boolean): VideoStep {
        previousOpen = action == Video.Cut
        baseUs = ptsUs
        bytes = size.toLong()
        asked = false
        return VideoStep(action, closePrevious = closePrevious)
    }

    private companion object {
        /** Sound arrives every ~20 ms; a second without any means it stalled. */
        const val DefaultCloseAfterUs = 1_000_000L
    }
}
