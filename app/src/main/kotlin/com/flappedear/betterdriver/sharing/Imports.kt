package com.flappedear.betterdriver.sharing

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.sessionTitle
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.tracks.Tracks
import com.flappedear.betterdriver.transfer.BundleFormatException
import com.flappedear.betterdriver.transfer.IncomingFiles
import com.flappedear.betterdriver.transfer.IncomingKind
import com.flappedear.betterdriver.transfer.SessionBundle
import com.flappedear.betterdriver.transfer.SessionInbox
import com.flappedear.betterdriver.transfer.TrackMatch
import com.flappedear.betterdriver.transfer.TrackTransfer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * A keep-or-replace choice; only ever asked after the user started an import, never during an outing.
 * [answer] gets true to replace, false to keep, null when dismissed (keeps too).
 */
class TransferQuestion(val title: String, val text: String, val answer: (replace: Boolean?) -> Unit)

/**
 * Bringing in sessions (with video) and tracks from the other device (FBBD-23, FBBD-80): a file the
 * user picks, or one opened with the app from Quick Share or a file manager. The file's first bytes
 * say whether it is a session bundle or a track file.
 *
 * A session is unpacked and checked before it is listed ([SessionInbox]); a damaged file adds
 * nothing. A session or track already here with other content needs a keep-or-replace answer
 * ([question]). Imports are refused while a session records, so that question never comes up
 * during an outing. One per process; disk work runs on one background thread.
 */
class Imports private constructor(private val context: Context) {
    private val store = SessionStore(sessionsDir(context))
    private val inbox = SessionInbox(store, File(context.filesDir, StagingDir))
    private val trackStore = TrackStore(tracksDir(context))
    private val io = Executors.newSingleThreadExecutor { Thread(it, "imports-io") }
    private val main = Handler(Looper.getMainLooper())

    /** The current or last import. */
    var state by mutableStateOf<TransferState?>(null)
        private set

    /** A choice the current import waits for. */
    var question by mutableStateOf<TransferQuestion?>(null)
        private set

    /** Goes up each time a file is opened with the app from outside, so the UI can show Sessions. */
    var openedFromOutside by mutableIntStateOf(0)
        private set

    init {
        // Whatever an import killed half way left behind.
        io.execute { inbox.clearStaging() }
    }

    /** Imports a session bundle or a track file. [fromOutside]: opened with the app from another app. */
    fun import(uri: Uri, fromOutside: Boolean = false) {
        if (state?.running == true || question != null) return
        if (fromOutside) openedFromOutside++
        state = TransferState("Reading the file…", running = true)
        io.execute { guarded { importNow(uri) } }
    }

    /** The question was dismissed without a choice: what is here stays. */
    fun dismissQuestion() {
        question?.answer?.invoke(null)
    }

    private fun importNow(uri: Uri) {
        if (store.list().any { it.state == SessionState.RECORDING }) {
            throw IOException("A session is recording. Import after it stops.")
        }
        when (IncomingFiles.kindOf(read(uri) { it.readNBytes(IncomingFiles.PeekBytes) })) {
            IncomingKind.SESSION -> receiveSession(uri)
            IncomingKind.TRACK -> addTrack(read(uri, TrackTransfer::read)) { finish(it) }
            IncomingKind.UNKNOWN -> throw BundleFormatException("this is not a BetterDriver session or track")
        }
    }

    private fun <T> read(uri: Uri, block: (InputStream) -> T): T =
        context.contentResolver.openInputStream(uri)?.use(block) ?: throw IOException("could not open the file")

    private fun receiveSession(uri: Uri) {
        val total = sizeOf(context, uri)
        val progress = progressOf(main, total) { f -> state = TransferState("Importing…", f, running = true) }
        val received = read(uri) { inbox.receive(it, progress) }
        val title = sessionTitle(SessionSummary(received.sessionId, File(""), received.info, SessionState.FINISHED))
        val decide = { replace: Boolean -> io.execute { guarded { accept(received, replace, title) } } }
        if (inbox.exists(received)) {
            ask(
                "Session already here",
                "The session of $title is already on this device. Keep the one here, or replace it " +
                    "with the one received?",
                onAnswer = decide,
                onCancel = { decide(false) },
            )
        } else {
            decide(false)
        }
    }

    private fun accept(received: SessionBundle.Received, replace: Boolean, title: String) {
        val added = inbox.accept(received, replace)
        Sessions.get(context).refresh()
        val what = if (added) "Imported the session of $title." else "Kept the session of $title already here."
        val track = received.track
        if (track == null) finish(what) else addTrack(track) { t -> finish("$what $t") }
    }

    /** Adds [track] if it is new, asks if it differs from the one here; [done] gets the outcome. io thread. */
    private fun addTrack(track: Track, done: (String) -> Unit) {
        val tracks = Tracks.get(context)
        val name = track.displayName
        when (TrackTransfer.match(track, trackStore.get(track.id))) {
            TrackMatch.NEW -> tracks.save(track) { done("Added track $name.") }
            TrackMatch.SAME -> done("Track $name is already here.")
            TrackMatch.DIFFERENT -> ask(
                "Track differs",
                "$name on this device has other lines. Keep the one here, or replace it with the one received?",
                onAnswer = { replace ->
                    if (replace) tracks.save(track) { done("Replaced track $name.") } else done("Kept track $name.")
                },
                onCancel = { done("Kept track $name.") },
            )
        }
    }

    private fun ask(title: String, text: String, onAnswer: (Boolean) -> Unit, onCancel: () -> Unit) = main.post {
        state = TransferState("Waiting for your choice…", running = true)
        question = TransferQuestion(title, text) { replace ->
            question = null
            state = TransferState("Importing…", running = true)
            if (replace == null) onCancel() else onAnswer(replace)
        }
    }

    private fun finish(message: String, failed: Boolean = false) =
        main.post { state = TransferState(if (failed) "Import failed: $message" else message, failed = failed) }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            android.util.Log.w(Tag, "import failed", e)
            finish(e.message ?: "could not read the file", failed = true)
        } catch (e: IllegalStateException) {
            android.util.Log.w(Tag, "import failed", e)
            finish(e.message ?: "could not store the file", failed = true)
        } catch (e: SecurityException) {
            android.util.Log.w(Tag, "import failed", e)
            finish("no access to the file", failed = true)
        }
    }

    companion object {
        private const val Tag = "Imports"
        private const val StagingDir = "transfer-staging"

        @Volatile
        private var instance: Imports? = null

        fun get(context: Context): Imports = instance ?: synchronized(this) {
            instance ?: Imports(context.applicationContext).also { instance = it }
        }
    }
}

/** The file's size for progress; 0 when the provider does not say. */
private fun sizeOf(context: Context, uri: Uri): Long = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    } ?: 0L
} catch (_: IllegalArgumentException) {
    0L
}
