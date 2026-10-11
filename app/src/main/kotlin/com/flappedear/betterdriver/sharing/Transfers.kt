package com.flappedear.betterdriver.sharing

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.FileProvider
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.transfer.SessionBundle
import com.flappedear.betterdriver.transfer.SessionBundleExtension
import com.flappedear.betterdriver.transfer.TrackTransfer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

/** Progress or result of one send, save or import, for the screen that started it. */
data class TransferState(
    val text: String,
    val fraction: Float? = null,
    val running: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Sending sessions (with video) and tracks to the other device (FBBD-23, FBBD-80).
 *
 * - Send prepares the file in the app's cache, then opens Android's share sheet, where Quick Share
 *   is one of the targets. Prepared files are removed after a few hours.
 * - Save file writes straight to a file the user picks (Downloads, a USB drive, any folder), with
 *   no copy in between, so it also works when the phone is short of space.
 *
 * Imports are in [Imports]. One per process; disk work runs on one background thread.
 */
class Transfers private constructor(private val context: Context) {
    private val store = SessionStore(sessionsDir(context))
    private val trackStore = TrackStore(tracksDir(context))
    private val io = Executors.newSingleThreadExecutor { Thread(it, "transfers-io") }
    private val main = Handler(Looper.getMainLooper())
    private val outgoingDir = File(context.cacheDir, OutgoingDir)

    /** Sends and saves, by [sessionKey] or [trackKey]. */
    val outgoing = mutableStateMapOf<String, TransferState>()

    init {
        io.execute { removeOld() }
    }

    /** Prepares the session's bundle, then hands its shareable address to [onReady] on the main thread. */
    fun sendSession(id: String, onReady: (Uri) -> Unit) = work(sessionKey(id)) { progress ->
        val dir = store.dir(id)
        val total = SessionBundle.size(dir)
        removeOld()
        if (outgoingDir.usableSpace < total + SpaceMarginBytes) {
            throw IOException("Not enough free space to prepare it. Use Save file instead.")
        }
        val file = File(outgoingDir, bundleName(id))
        val part = File(outgoingDir, file.name + ".part")
        try {
            FileOutputStream(part).use { SessionBundle.write(store, dir, trackOf(id), it, progress(total)) }
            if (!part.renameTo(file)) throw IOException("could not prepare ${file.name}")
        } finally {
            part.delete()
        }
        handOver(file, onReady)
        "Ready to send"
    }

    /** Writes the session's bundle to [uri], a file the user picked. */
    fun saveSession(id: String, uri: Uri) = work(sessionKey(id)) { progress ->
        val dir = store.dir(id)
        val total = SessionBundle.size(dir)
        open(uri).use { SessionBundle.write(store, dir, trackOf(id), it, progress(total)) }
        "Saved"
    }

    /** Writes the track file to the cache and hands its shareable address to [onReady]. */
    fun sendTrack(track: Track, onReady: (Uri) -> Unit) = work(trackKey(track.id)) { _ ->
        removeOld()
        val file = File(outgoingDir, TrackTransfer.fileName(track))
        file.writeText(trackText(track))
        handOver(file, onReady)
        "Ready to send"
    }

    fun saveTrack(track: Track, uri: Uri) = work(trackKey(track.id)) { _ ->
        open(uri).use { it.write(trackText(track).toByteArray(Charsets.UTF_8)) }
        "Saved"
    }

    /** The saved copy, so an edit made since the list loaded is sent too. */
    private fun trackText(track: Track) = TrackJson.encode(trackStore.get(track.id) ?: track)

    private fun handOver(file: File, onReady: (Uri) -> Unit) {
        val uri = FileProvider.getUriForFile(context, context.packageName + AuthoritySuffix, file)
        main.post { onReady(uri) }
    }

    /** Runs [block] for [key] with progress; [block] returns the message shown when it succeeds. */
    private fun work(key: String, block: (progress: (Long) -> (Int) -> Unit) -> String) {
        if (outgoing[key]?.running == true) return
        outgoing[key] = TransferState("Preparing…", 0f, running = true)
        io.execute {
            val result = try {
                TransferState(
                    block { total ->
                        progressOf(main, total) { f ->
                            outgoing[key] = TransferState("Preparing…", f, running = true)
                        }
                    },
                )
            } catch (e: IOException) {
                android.util.Log.w(Tag, "send failed", e)
                TransferState(e.message ?: "Failed", failed = true)
            } catch (e: IllegalStateException) {
                android.util.Log.w(Tag, "send failed", e) // unreadable metadata
                TransferState("Failed: the session could not be read", failed = true)
            } catch (e: SecurityException) {
                android.util.Log.w(Tag, "send failed", e)
                TransferState("Failed: no access to that file", failed = true)
            }
            main.post { outgoing[key] = result }
        }
    }

    /** The saved track the session was timed with, as it is now. */
    @Suppress("TooGenericExceptionCaught") // A damaged session still sends, without its track.
    private fun trackOf(id: String): Track? = try {
        TrackEvent.savedTrackId(store.readEvents(id).events)?.let(trackStore::get)
    } catch (e: RuntimeException) {
        android.util.Log.w(Tag, "no track for $id", e)
        null
    }

    private fun open(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open the file")

    /** Removes prepared files older than [KeepPreparedMillis], so a Quick Share under way is never cut off. */
    private fun removeOld() {
        outgoingDir.mkdirs()
        val now = System.currentTimeMillis()
        outgoingDir.listFiles()?.filter { now - it.lastModified() >= KeepPreparedMillis }?.forEach { it.delete() }
    }

    companion object {
        private const val Tag = "Transfers"
        private const val OutgoingDir = "outgoing"

        /** Must match the provider in the manifest. */
        private const val AuthoritySuffix = ".transfer"

        private const val KeepPreparedMillis = 6 * 60 * 60 * 1000L
        private const val SpaceMarginBytes = 200L shl 20

        /** Generic type: a bundle has no registered one, and it keeps the file name. */
        const val MimeType = "application/octet-stream"

        fun sessionKey(id: String) = "session:$id"
        fun trackKey(id: String) = "track:$id"
        fun bundleName(id: String) = "BetterDriver-$id.$SessionBundleExtension"

        @Volatile
        private var instance: Transfers? = null

        fun get(context: Context): Transfers = instance ?: synchronized(this) {
            instance ?: Transfers(context.applicationContext).also { instance = it }
        }
    }
}

private const val Percent = 100

/** Counts bytes and posts [show] on [main] at most once per percent. */
internal fun progressOf(main: Handler, total: Long, show: (Float) -> Unit): (Int) -> Unit {
    var done = 0L
    var shown = -1
    return { n ->
        done += n
        val percent = if (total > 0) (done * Percent / total).toInt().coerceAtMost(Percent) else 0
        if (percent != shown) {
            shown = percent
            main.post { show(percent / Percent.toFloat()) }
        }
    }
}

/** Opens Android's share sheet (Quick Share and the rest) for a prepared file. */
fun share(context: Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(Transfers.MimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    try {
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: ActivityNotFoundException) {
        android.util.Log.w("Transfers", "no share target", e)
    }
}
