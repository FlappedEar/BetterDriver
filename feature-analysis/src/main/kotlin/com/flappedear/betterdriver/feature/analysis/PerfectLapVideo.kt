package com.flappedear.betterdriver.feature.analysis

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.flappedear.betterdriver.analysis.line.PerfectLapScene
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** The saved video's size and rate (UI-180 A: the player's picture, kept as a video for the phone). */
const val PerfectLapVideoWidth = 1920
const val PerfectLapVideoHeight = 1080
const val PerfectLapVideoFps = 30
private const val BitRate = 8_000_000
private const val KeyFrameSeconds = 1
private const val TimeoutMicros = 10_000L
private const val MicrosPerSecond = 1_000_000L

/**
 * Draws the perfect lap of [scene] frame by frame with [painter] into an H.264 MP4, as fast as
 * the encoder takes it, reporting [progress] from 0 to 1, then saves it to the phone's Movies
 * (Movies/BetterDriver) and returns where it is. Cancelling stops it and leaves nothing behind.
 * A phone whose encoder refuses the format throws [IllegalArgumentException] or
 * [IllegalStateException]. Run off the main thread.
 */
internal suspend fun savePerfectLapVideo(
    context: Context,
    scene: PerfectLapScene,
    painter: PerfectLapPainter,
    name: String,
    progress: (Float) -> Unit,
): SavedVideo {
    val file = File(File(context.cacheDir, "perfect-lap").apply { mkdirs() }, "$name.mp4")
    try {
        encode(scene, painter, file, progress)
        return publish(context, file, name)
    } finally {
        file.delete()
    }
}

// One encoder, start to end, in the order MediaCodec needs.
@Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
private suspend fun encode(scene: PerfectLapScene, painter: PerfectLapPainter, file: File, progress: (Float) -> Unit) {
    val mime = MediaFormat.MIMETYPE_VIDEO_AVC
    val format = MediaFormat.createVideoFormat(mime, PerfectLapVideoWidth, PerfectLapVideoHeight).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, BitRate)
        setInteger(MediaFormat.KEY_FRAME_RATE, PerfectLapVideoFps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KeyFrameSeconds)
        // Frames come out in the order they go in, so each gets its own time (below).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val surface = try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.createInputSurface()
    } catch (e: IllegalArgumentException) {
        codec.release()
        throw e
    } catch (e: IllegalStateException) {
        codec.release()
        throw e
    }
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val info = MediaCodec.BufferInfo()
    var track = -1
    var written = 0L
    fun drain(end: Boolean) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, TimeoutMicros)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !config && track >= 0) {
                        // A canvas frame carries the time it was posted, not its time in the lap.
                        info.presentationTimeUs = written * MicrosPerSecond / PerfectLapVideoFps
                        muxer.writeSampleData(track, buf, info)
                        written++
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
    try {
        codec.start()
        val frames = (scene.durationSeconds * PerfectLapVideoFps).toInt() + 1
        for (n in 0 until frames) {
            currentCoroutineContext().ensureActive()
            val canvas = surface.lockHardwareCanvas()
            try {
                painter.draw(canvas, n.toDouble() / PerfectLapVideoFps, PerfectLapVideoWidth, PerfectLapVideoHeight)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            drain(end = false)
            progress((n + 1).toFloat() / frames)
        }
        codec.signalEndOfInputStream()
        drain(end = true)
    } finally {
        runCatching { codec.stop() }
        codec.release()
        surface.release()
        if (track >= 0) runCatching { muxer.stop() }
        muxer.release()
    }
}

/** Copies [file] to Movies/BetterDriver, where the phone's gallery and Quick Share find it. */
@Suppress("NestedBlockDepth") // A pending entry, written, then shown or removed.
private fun publish(context: Context, file: File, name: String): SavedVideo {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BetterDriver")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
            "no place for the video"
        }
        try {
            val out = checkNotNull(resolver.openOutputStream(uri)) { "cannot write the video" }
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: java.io.IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return SavedVideo(uri, inMovies = true)
    }
    // Android 8 and 9: the public Movies folder needs a storage permission the app does not ask
    // for, so the video stays in the app's own Movies folder and is sent through the file provider.
    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "BetterDriver").apply { mkdirs() }
    val out = File(dir, "$name.mp4")
    file.copyTo(out, overwrite = true)
    return SavedVideo(FileProvider.getUriForFile(context, context.packageName + ".transfer", out), inMovies = false)
}
