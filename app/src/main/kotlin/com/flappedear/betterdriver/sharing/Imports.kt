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
import com.flappedear.betterdriver.gopro.GoPros
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recording.DeleteState
import com.flappedear.betterdriver.recording.ExportState
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.circuitsDir
import com.flappedear.betterdriver.recording.sessionTitle
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.timing.circuits.CircuitDirectory
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.timing.track.TrackStore
import com.flappedear.betterdriver.tracks.Tracks
import com.flappedear.betterdriver.transfer.BundleFormatException
import com.flappedear.betterdriver.transfer.IncomingFiles
import com.flappedear.betterdriver.transfer.IncomingKind
import com.flappedear.betterdriver.transfer.SessionBundle
import com.flappedear.betterdriver.transfer.SessionInbox
import com.flappedear.betterdriver.transfer.TrackMatch
import com.flappedear.betterdriver.transfer.TrackTransfer
import com.flappedear.betterdriver.transfer.foreign.ImportFormatException
import com.flappedear.betterdriver.transfer.foreign.ImportLimits
import com.flappedear.betterdriver.transfer.foreign.ImportedSession
import com.flappedear.betterdriver.transfer.foreign.SessionImport
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
 * say whether it is a session bundle or a track file. Anything else is tried as a session from
 * another app, a VBO, GPX or RaceChrono CSV file (FBBD-164, [SessionImport]).
 *
 * A session is unpacked and checked before it is listed ([SessionInbox]); a damaged file adds
 * nothing. A session or track already here with other content needs a keep-or-replace answer
 * ([question]). Imports are refused while a session records, so that question never comes up
 * during an outing. One per process; disk work runs on one background thread.
 */
@Suppress("TooManyFunctions") // One small step per kind of file and per moment of an import.
class Imports private constructor(private val context: Context) {
    // Made on the io thread when first used: making them reads the disk (FBBD-41).
    private val store by lazy { SessionStore(sessionsDir(context)) }
    private val inbox by lazy { SessionInbox(store, File(context.filesDir, StagingDir)) }
    private val trackStore by lazy { TrackStore(tracksDir(context)) }
    private val foreign by lazy { SessionImport(store, File(context.filesDir, StagingDir)) }
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
        // Whatever an import killed half way left behind. The list may have been read without it.
        io.execute { if (inbox.clearStaging()) Sessions.get(context).refresh() }
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

    @Suppress("ThrowsCount") // One reason per file or moment that cannot be imported.
    private fun importNow(uri: Uri) {
        refuseWhileRecording(store)
        if (AppDataGate.restoring) throw IOException("a backup is being restored. Import after it ends.")
        when (IncomingFiles.kindOf(read(uri, IncomingFiles::head))) {
            IncomingKind.SESSION -> receiveSession(uri)
            IncomingKind.TRACK -> addTrack(read(uri, TrackTransfer::read), { finish(it) }) { name ->
                finish("could not store track $name", failed = true)
            }
            IncomingKind.BACKUP -> throw BundleFormatException("this is a backup. Restore it in Settings › App.")
            // VBO, GPX or RaceChrono CSV, possibly gzip-compressed; SessionImport tells by the content.
            IncomingKind.UNKNOWN -> importForeign(uri)
        }
    }

    /**
     * A session from another app (FBBD-164): read into a new session with no video, its track
     * detected as a recording's is. Refused while a session records, checked again just before it is
     * added. Under [AppDataGate], so a restore never swaps the sessions folder under it.
     */
    private fun importForeign(uri: Uri) {
        val total = sizeOf(context, uri)
        if (total > ImportLimits.MaxBytes) {
            throw ImportFormatException("the file is too large (over ${ImportLimits.MaxMegabytes} MB)")
        }
        val progress = progressOf(main, total) { f -> state = TransferState("Reading the session…", f, running = true) }
        val tracks = trackStore.all()
        val circuits = CircuitDirectory(circuitsDir(context)).load()
        val name = displayNameOf(context, uri)
        val made = AppDataGate.write {
            read(uri) { input ->
                foreign.import(input, name, tracks, circuits, progress, beforeAdd = { refuseWhileRecording(store) })
            }
        }
        DebugLog.i(Tag, "imported ${made.format.label} as ${made.id}: ${made.points} points, ${made.skipped} skipped")
        Sessions.get(context).refresh()
        finish(importedText(made, sessionTitle(SessionSummary(made.id, File(""), made.info, SessionState.FINISHED))))
    }

    private fun <T> read(uri: Uri, block: (InputStream) -> T): T =
        context.contentResolver.openInputStream(uri)?.use(block) ?: throw IOException("could not open the file")

    private fun receiveSession(uri: Uri) {
        val total = sizeOf(context, uri)
        val progress = progressOf(main, total) { f -> state = TransferState("Importing…", f, running = true) }
        val received = read(uri) { inbox.receive(it, progress) }
        refuseWhileRecording(store) { inbox.discard(received) }
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
        refuseWhileRecording(store) { inbox.discard(received) }
        if (replace && sessionBusy(context, received.sessionId)) {
            inbox.discard(received)
            throw IOException("the session of $title is busy (GoPro video, export, send or delete). Try again after.")
        }
        val added = AppDataGate.write { inbox.accept(received, replace) }
        Sessions.get(context).refresh()
        val what = if (added) "Imported the session of $title." else "Kept the session of $title already here."
        val track = received.track
        if (track == null) {
            finish(what)
        } else {
            addTrack(track, { t -> finish("$what $t") }) { name ->
                finish("$what Could not store track $name.", failed = true, prefixed = false)
            }
        }
    }

    /**
     * Adds [track] if it is new, asks if it differs from the one here; [done] gets the outcome,
     * [failed] the track's name when it could not be stored. io thread.
     */
    private fun addTrack(track: Track, done: (String) -> Unit, failed: (String) -> Unit) {
        val tracks = Tracks.get(context)
        val name = track.displayName
        val here = trackStore.get(track.id)
        // Stored only if the track here is still the one decided on; a restore (FBBD-156) or an edit
        // in between means deciding again.
        val save = { message: String ->
            tracks.save(
                track,
                onFailed = { failed(name) },
                still = { it == here },
                onChanged = { io.execute { guarded { addTrack(track, done, failed) } } },
            ) { done(message) }
        }
        when (TrackTransfer.match(track, here)) {
            TrackMatch.NEW -> save("Added track $name.")
            TrackMatch.SAME -> done("Track $name is already here.")
            TrackMatch.DIFFERENT -> ask(
                "Track differs",
                "$name on this device has other lines. Keep the one here, or replace it with the one received?",
                onAnswer = { replace -> if (replace) save("Replaced track $name.") else done("Kept track $name.") },
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

    private fun finish(message: String, failed: Boolean = false, prefixed: Boolean = failed) =
        main.post { state = TransferState(if (prefixed) "Import failed: $message" else message, failed = failed) }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            DebugLog.w(Tag, "import failed", e)
            finish(e.message ?: "could not read the file", failed = true)
        } catch (e: IllegalStateException) {
            DebugLog.w(Tag, "import failed", e)
            finish(e.message ?: "could not store the file", failed = true)
        } catch (e: SecurityException) {
            DebugLog.w(Tag, "import failed", e)
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

/**
 * A recording can start while a long import runs, so this is checked before reading, again before
 * asking and before anything is stored. [onRefused] cleans up first.
 */
private fun refuseWhileRecording(store: SessionStore, onRefused: () -> Unit = {}) {
    if (store.list().any { it.state == SessionState.RECORDING }) {
        onRefused()
        throw IOException("A session is recording. Import after it stops.")
    }
}

/**
 * Whether other work reads or writes session [id]'s folder: GoPro video being added, a VBO export, a
 * send or save, or a delete. Replacing the folder under it would mix the two sessions.
 */
private fun sessionBusy(context: Context, id: String): Boolean {
    val sessions = Sessions.get(context)
    return GoPros.get(context).work[id] != null ||
        Transfers.get(context).outgoing[Transfers.sessionKey(id)]?.running == true ||
        sessions.exports[id] == ExportState.Running ||
        sessions.deletes[id] == DeleteState.Running
}

/**
 * The message after a session from another app was imported (FBBD-164): its format, when it was,
 * where the track detection put it, how many positions it holds and what was left out.
 */
internal fun importedText(made: ImportedSession, title: String): String {
    val place = when (val d = made.detection) {
        is TrackDetection.Saved ->
            " at ${d.track.displayName}" + if (d.track.timeable) "" else " (no start/finish line yet, so no laps)"
        is TrackDetection.Directory -> " at ${d.circuit.name} (not one of your tracks yet, so no laps)"
        TrackDetection.Unknown -> ""
    }
    val skipped = when (made.skipped) {
        0 -> ""
        1 -> " 1 row could not be read and was left out."
        else -> " ${made.skipped} rows could not be read and were left out."
    }
    return "Imported the ${made.format.label} session of $title$place: ${made.points} positions.$skipped"
}

/** The file's name as the picker shows it, for an imported session's card; empty when unknown. */
private fun displayNameOf(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    }
} catch (_: IllegalArgumentException) {
    null
}.orEmpty()

/** The file's size for progress; 0 when the provider does not say. */
internal fun sizeOf(context: Context, uri: Uri): Long = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    } ?: 0L
} catch (_: IllegalArgumentException) {
    0L
}
