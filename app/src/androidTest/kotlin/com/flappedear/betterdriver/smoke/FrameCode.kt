package com.flappedear.betterdriver.smoke

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads the frame number the review fixture videos carry (ReviewFixtures, tools/review-fixtures):
 * a 12-bit Gray code in a row of twelve cells (y 40-52), its complement in the row under it (52-64). A picture
 * of any size is read at the cells' centres, so a player's surface scaled to the screen reads the
 * same as the 192 x 112 file.
 */
object FrameCode {
    private const val Bits = 12
    private const val CodeWidth = 192f
    private const val CodeHeight = 112f
    private const val CellWidth = 16f
    private const val TopRowY = 46f
    private const val BottomRowY = 58f
    private const val Threshold = 128

    /**
     * The frame number [bitmap] shows, or null when it shows no readable code (no picture yet, or a
     * frame torn mid-change).
     */
    fun read(bitmap: Bitmap): Int? {
        var gray = 0
        for (c in 0 until Bits) {
            val x = ((c * CellWidth + CellWidth / 2) / CodeWidth * bitmap.width).toInt()
            val top = luma(bitmap.getPixel(x, (TopRowY / CodeHeight * bitmap.height).toInt())) > Threshold
            val bottom = luma(bitmap.getPixel(x, (BottomRowY / CodeHeight * bitmap.height).toInt())) > Threshold
            if (top == bottom) return null
            val bit = if (top) 1 else 0
            gray = (gray shl 1) or bit
        }
        return fromGray(gray)
    }

    private fun fromGray(gray: Int): Int {
        var n = 0
        var g = gray
        while (g != 0) {
            n = n xor g
            g = g shr 1
        }
        return n
    }

    private fun luma(argb: Int) = (Color.red(argb) + Color.green(argb) + Color.blue(argb)) / 3

    /** The code of [retriever]'s frame nearest to [micros] of media time; null when unreadable. */
    fun inFile(retriever: MediaMetadataRetriever, micros: Long): Int? =
        retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)?.let { read(it) }

    /** Opens [file] for [inFile]; release it after. */
    fun retriever(file: File) = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
}

/**
 * The video surfaces on screen and what they show, read with PixelCopy straight from each surface
 * (what the player last put there, not the view drawn over it).
 */
class SurfaceFrames(private val activity: Activity) {
    private val thread = HandlerThread("pixel-copy").apply { start() }
    private val handler = Handler(thread.looper)

    /** The SurfaceViews in the window, top to bottom (in the lap review: lap A, then lap B). */
    fun surfaces(): List<SurfaceView> {
        val found = ArrayList<Pair<Int, SurfaceView>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            collect(activity.window.decorView) { v ->
                if (v.isShown && v.width > 0 && v.height > 0) {
                    val at = IntArray(2)
                    v.getLocationOnScreen(at)
                    found += at[1] to v
                }
            }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** The frame number [view] shows, or null when its picture cannot be read now. */
    fun frameOf(view: SurfaceView): Int? {
        val bitmap = Bitmap.createBitmap(CopyWidth, CopyHeight, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        val requested = try {
            PixelCopy.request(view, bitmap, { r -> result = r; done.countDown() }, handler)
            true
        } catch (_: IllegalArgumentException) {
            // The surface is not valid (yet, or any more).
            false
        }
        val copied = requested && done.await(CopyTimeoutMs, TimeUnit.MILLISECONDS) && result == PixelCopy.SUCCESS
        val frame = if (copied) FrameCode.read(bitmap) else null
        bitmap.recycle()
        return frame
    }

    fun close() {
        thread.quitSafely()
    }

    private fun collect(v: View, into: (SurfaceView) -> Unit) {
        if (v is SurfaceView) into(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), into)
    }

    private companion object {
        /** The fixture videos' own size: the copy is scaled to it. */
        const val CopyWidth = 192
        const val CopyHeight = 112
        const val CopyTimeoutMs = 1_000L
    }
}
