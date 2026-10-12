package com.flappedear.betterdriver.sharing

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.BuildConfig
import com.flappedear.betterdriver.coach.CoachPicksRepo
import com.flappedear.betterdriver.coach.CoachPrefs
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.shell.restoreResultText
import com.flappedear.betterdriver.tracks.Tracks
import com.flappedear.betterdriver.transfer.Backup
import com.flappedear.betterdriver.transfer.BackupRestore
import com.flappedear.betterdriver.transfer.BackupSummary
import com.flappedear.betterdriver.transfer.DataGate
import com.flappedear.betterdriver.transfer.RestoreMode
import com.flappedear.betterdriver.transfer.UnpackedBackup
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Holds the app's own writes (tracks, setup, coach settings and picks, imports) while a restore
 * swaps the data, so none is lost between the copy and the swap (FBBD-156).
 */
internal val AppDataGate = DataGate()

/** A checked backup waiting for Merge or Replace on the restore sheet. */
class RestoreOffer internal constructor(val summary: BackupSummary, internal val backup: UnpackedBackup)

/**
 * Backup and restore of the app's data (FBBD-156), from Settings › App. Back up writes one file the
 * user picks ([Backup]); Restore reads one, checks all of it, offers it ([offer]) and then merges
 * or replaces atomically ([BackupRestore]). Both are refused while a session records, as imports
 * are. One per process; disk work runs on one background thread, with progress in [state].
 */
@Suppress("TooManyFunctions") // Back up, check, restore and the small steps they share.
class Backups private constructor(private val context: Context) {
    private val root = context.filesDir
    private val restorer = BackupRestore(root, File(root, StagingDir))
    private val store = SessionStore(sessionsDir(context))
    private val io = Executors.newSingleThreadExecutor { Thread(it, "backups-io") }
    private val main = Handler(Looper.getMainLooper())

    /** The current or last backup or restore. */
    var state by mutableStateOf<TransferState?>(null)
        private set

    /** A checked backup the restore sheet shows. */
    var offer by mutableStateOf<RestoreOffer?>(null)
        private set

    private val busy get() = state?.running == true || offer != null

    init {
        // A check or restore the app was killed in; a committed restore was finished at start.
        io.execute { restorer.clearStaging() }
    }

    /** Sessions "Include sessions" would add and their size without video, for the backup dialog. */
    fun sessionsSize(onReady: (Pair<Int, Long>) -> Unit) = io.execute {
        val size = Backup.sessionsSize(root)
        main.post { onReady(size) }
    }

    /** Writes a backup to [uri], a file the user picked. */
    fun backUp(uri: Uri, includeSessions: Boolean) {
        if (busy) return
        state = TransferState("Backing up…", 0f, running = true)
        io.execute {
            val result = guarded("Backup failed", onFailed = { delete(uri) }) {
                refuseWhileRecording()
                val total = Backup.size(root, includeSessions)
                val progress = progressOf(main, total) { f -> state = TransferState("Backing up…", f, running = true) }
                openOutput(uri).use { Backup.write(root, prefs(), AppVersion, includeSessions, it, onBytes = progress) }
                "Backed up."
            }
            main.post { state = result }
        }
    }

    /** Reads and checks the backup at [uri]; when it is good, [offer] holds it for Merge or Replace. */
    fun open(uri: Uri) {
        if (busy) return
        state = TransferState("Checking the backup…", 0f, running = true)
        io.execute {
            var checked: UnpackedBackup? = null
            val result = guarded("Restore failed") {
                refuseWhileRecording()
                val total = sizeOf(context, uri)
                if (total > 0 && root.usableSpace < total + SpaceMarginBytes) {
                    throw IOException("not enough free space to check it")
                }
                val progress = progressOf(main, total) { f ->
                    state = TransferState("Checking the backup…", f, running = true)
                }
                checked = read(uri) { restorer.unpack(it, progress) }
                ""
            }
            main.post {
                val backup = checked
                if (backup != null) {
                    state = null
                    offer = RestoreOffer(backup.summary, backup)
                } else {
                    state = result
                }
            }
        }
    }

    /** Restores the offered backup. */
    fun restore(mode: RestoreMode) {
        val o = offer ?: return
        offer = null
        state = TransferState("Restoring…", running = true)
        io.execute {
            val result = guarded("Restore failed", onFailed = { restorer.discard(o.backup) }) {
                refuseWhileRecording()
                refuseWhileImporting()
                // Writes made meanwhile wait; values taken before are dropped and [reload] shows the result.
                val done = AppDataGate.exclusive {
                    try {
                        restorer.restore(o.backup, mode, prefs(), ::refuseWhileRecording) { writePrefs(context, it) }
                    } finally {
                        // Still inside the hold: no waiting write may land in a half-swapped folder.
                        if (restorer.pending) finishAfterFailure()
                    }
                }
                main.post(::reload)
                restoreResultText(done)
            }
            main.post { state = result }
        }
    }

    /**
     * A restore that failed after its commit left the data part restored. Finishing it is tried once
     * more; if that fails too, the app shows [unfinished] (the restore-unfinished screen) until
     * [finishPending] completes it. Called while the restore still holds [AppDataGate].
     */
    private fun finishAfterFailure() {
        try {
            restorer.finishPending { writePrefs(context, it) }
            main.post(::reload)
        } catch (e: IOException) {
            DebugLog.e(Tag, "could not finish the restore", e)
            markUnfinished()
        }
    }

    /** The restore sheet was closed: nothing changes. */
    fun cancel() {
        val o = offer ?: return
        offer = null
        io.execute { restorer.discard(o.backup) }
    }

    /** Screens that keep the data in memory read it again. */
    private fun reload() {
        SetupRepo.get(context).reload()
        CoachPrefs.get(context).reload()
        CoachPicksRepo.get(context).loadAll()
        Tracks.get(context).refresh()
        Sessions.get(context).refresh()
    }

    /** The app's preferences (AppPrefs); other preference files hold device state and are left out. */
    private fun prefs(): Map<String, Any?> =
        context.getSharedPreferences(AppPrefs.FileName, Context.MODE_PRIVATE).all

    /** A recording can start while a long backup or restore runs, so this is checked again before the swap. */
    private fun refuseWhileRecording() {
        if (store.list().any { it.state == SessionState.RECORDING }) {
            throw IOException("a session is recording. Try again after it stops.")
        }
    }

    private fun refuseWhileImporting() {
        if (Imports.get(context).state?.running == true) {
            throw IOException("an import is running. Try again after it ends.")
        }
    }

    private fun <T> read(uri: Uri, block: (InputStream) -> T): T =
        context.contentResolver.openInputStream(uri)?.use(block) ?: throw IOException("could not open the file")

    private fun openOutput(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open the file")

    /** Removes a backup file left half written. */
    private fun delete(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .onFailure { DebugLog.w(Tag, "could not remove the unfinished backup", it) }
    }

    /** Runs [block], which returns the message for success; [onFailed] cleans up after an error. */
    private fun guarded(prefix: String, onFailed: () -> Unit = {}, block: () -> String): TransferState = try {
        TransferState(block())
    } catch (e: IOException) {
        failed(prefix, e.message ?: "could not read or write the file", e, onFailed)
    } catch (e: IllegalStateException) {
        failed(prefix, e.message ?: "could not store the data", e, onFailed)
    } catch (e: SecurityException) {
        failed(prefix, "no access to the file", e, onFailed)
    }

    private fun failed(prefix: String, why: String, e: Exception, onFailed: () -> Unit): TransferState {
        DebugLog.w(Tag, prefix, e)
        onFailed()
        return TransferState("$prefix: $why", failed = true)
    }

    companion object {
        private const val Tag = "Backups"
        private const val StagingDir = "backup-staging"
        private const val SpaceMarginBytes = 200L shl 20
        private const val FinishTries = 3
        private val AppVersion = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

        @Volatile
        private var instance: Backups? = null

        @Volatile
        private var pendingChecked = false

        /**
         * True while a committed restore is not finished in this process: the app shows the
         * restore-unfinished screen instead of anything that reads the data.
         */
        var unfinished by mutableStateOf(false)
            private set

        @Synchronized
        private fun markUnfinished() {
            pendingChecked = false
            Handler(Looper.getMainLooper()).post { unfinished = true }
        }

        fun get(context: Context): Backups = instance ?: synchronized(this) {
            instance ?: Backups(context.applicationContext).also { instance = it }
        }

        /**
         * Finishes a restore the app was killed in after its commit (renames only), so nothing reads
         * half-restored data. Call before the setup, tracks or sessions are read; usually it only
         * looks for the marker file. Returns false when it still fails after a few tries: the data
         * is then part old, part restored, nothing may read it, and the marker stays for the next try.
         */
        /**
         * Main thread. Also the Try again of the restore-unfinished screen: once finished, data
         * already loaded in this process is read again.
         */
        @Synchronized
        fun finishPending(context: Context): Boolean {
            val root = context.filesDir
            val restorer = BackupRestore(root, File(root, StagingDir))
            var tries = 0
            while (!pendingChecked && tries < FinishTries) {
                tries++
                try {
                    if (restorer.finishPending { writePrefs(context, it) }) {
                        DebugLog.i(Tag, "finished a restore the app was stopped in")
                        instance?.main?.post { instance?.reload() }
                    }
                    pendingChecked = true
                } catch (e: IOException) {
                    DebugLog.e(Tag, "could not finish the restore (try $tries)", e)
                }
            }
            unfinished = !pendingChecked
            return pendingChecked
        }

        /** Replaces the app's preferences with [prefs]; throws if they could not be written. */
        private fun writePrefs(context: Context, prefs: Map<String, Any>) {
            val edit = context.getSharedPreferences(AppPrefs.FileName, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in prefs) {
                when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                }
            }
            if (!edit.commit()) throw IOException("could not write the app settings")
        }
    }
}
