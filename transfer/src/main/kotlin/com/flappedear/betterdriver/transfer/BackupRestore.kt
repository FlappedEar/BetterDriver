package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupJson
import com.flappedear.betterdriver.timing.track.Track
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Restores a backup over the app's data in [root] (its files folder), atomically (FBBD-156).
 *
 * The file is first unpacked into [staging] and checked whole ([unpack]); nothing here changes if it
 * is damaged. [restore] then builds every changed folder in full next to the data (`new/`), writes
 * a commit marker, and only then swaps the folders in with renames, keeping the old ones aside
 * (`old/`) until the end. If the app dies before the marker, the old data stays and the work is
 * dropped ([clearStaging]); after it, the next start finishes the swap ([finishPending]). Either
 * way the data is the old state or the restored one, never a mix.
 *
 * Sessions are only ever added: one already here is kept, never replaced by a copy without video.
 * [staging] must be on the same file system as [root]. Not thread-safe; one restore at a time.
 */
@Suppress("TooManyFunctions") // One step per part of the data, each small.
class BackupRestore(private val root: File, private val staging: File) {
    private val work get() = File(staging, "restore")
    private val newDir get() = File(work, "new")
    private val oldDir get() = File(work, "old")
    private val commitFile get() = File(work, "commit")
    private val prefsFile get() = File(work, "prefs.properties")

    /** Unpacks and checks a backup; see [Backup.unpack]. */
    fun unpack(input: InputStream, onBytes: (Int) -> Unit = {}): UnpackedBackup {
        staging.mkdirs()
        return Backup.unpack(input, staging, onBytes)
    }

    /** Drops an unpacked backup that will not be restored. */
    fun discard(backup: UnpackedBackup) {
        backup.dir.deleteRecursively()
    }

    /**
     * Restores [backup] in [mode]. [prefsHere] are the app's preferences now; [writePrefs] replaces
     * them all with what it is given, and may be called again by [finishPending]. [beforeCommit]
     * runs after everything is prepared and before anything changes; it throws to stop (for
     * example when a session started recording meanwhile). The backup is used up either way.
     */
    fun restore(
        backup: UnpackedBackup,
        mode: RestoreMode,
        prefsHere: Map<String, Any?>,
        beforeCommit: () -> Unit = {},
        writePrefs: (Map<String, Any>) -> Unit,
    ): RestoreResult {
        var committed = false
        try {
            work.deleteRecursively()
            if (!newDir.mkdirs()) throw IOException("could not prepare the restore")
            val result = prepare(backup, mode, BackupPrefs.kept(prefsHere))
            beforeCommit()
            // Each prepared file is synced; its folders are too before the marker makes them the data.
            syncDirs(newDir)
            writeSynced(commitFile, mode.name)
            committed = true
            apply(writePrefs)
            return result
        } finally {
            if (!committed) work.deleteRecursively()
            discard(backup)
        }
    }

    /**
     * Finishes a restore the app was killed in after its commit marker. Call at start, before
     * anything reads the data. Quick: renames only. Returns whether one was finished.
     */
    fun finishPending(writePrefs: (Map<String, Any>) -> Unit): Boolean {
        if (!commitFile.isFile) return false
        apply(writePrefs)
        return true
    }

    /** Whether a committed restore is still to be finished ([finishPending]). */
    val pending: Boolean get() = commitFile.isFile

    /** Removes what an unfinished check or restore left behind. Not while one runs. */
    fun clearStaging() {
        if (!commitFile.isFile) staging.deleteRecursively()
    }

    private fun prepare(backup: UnpackedBackup, mode: RestoreMode, prefsHere: Map<String, Any>): RestoreResult {
        val (tracksAdded, tracksUpdated) = prepareTracks(backup, mode)
        val (equipmentAdded, carsAdded) = prepareSetup(backup, mode)
        val files = prepareFiles(backup, Backup.Coach, mode) + prepareFiles(backup, Backup.Calibrations, mode)
        val prefsChanged = preparePrefs(backup, mode, prefsHere)
        val (sessionsAdded, sessionsKept) = prepareSessions(backup)
        return RestoreResult(
            tracksAdded = tracksAdded,
            tracksUpdated = tracksUpdated,
            equipmentAdded = equipmentAdded,
            carsAdded = carsAdded,
            sessionsAdded = sessionsAdded,
            sessionsKept = sessionsKept,
            filesRestored = files,
            prefsChanged = prefsChanged,
        )
    }

    /** Tracks: merge keeps the newer of each; replace takes the backup's set. Returns added, updated. */
    private fun prepareTracks(backup: UnpackedBackup, mode: RestoreMode): Pair<Int, Int> {
        val live = File(root, Backup.Tracks)
        val target = File(newDir, Backup.Tracks).apply { mkdirs() }
        if (mode == RestoreMode.MERGE) copyTree(live, target)
        var added = 0
        var updated = 0
        for (f in File(backup.dir, Backup.Tracks).listFiles().orEmpty()) {
            val incoming = Backup.readTrack(f)
            val here = trackHere(File(live, f.name))
            val take = mode == RestoreMode.REPLACE || here == null || RestoreRules.newer(incoming, here)
            if (!take) continue
            copyFile(f, File(target, f.name))
            when {
                here == null -> added++
                TrackTransfer.match(incoming, here) != TrackMatch.SAME -> updated++
            }
        }
        // Nothing to swap when a merge changes nothing.
        if (mode == RestoreMode.MERGE && added + updated == 0) target.deleteRecursively()
        return added to updated
    }

    /** A track here, or null when there is none or it cannot be read (the backup's then wins). */
    private fun trackHere(file: File): Track? = try {
        if (file.isFile) Backup.readTrack(file) else null
    } catch (_: IOException) {
        null
    }

    /** Equipment and cars. Returns how many of each are new. */
    private fun prepareSetup(backup: UnpackedBackup, mode: RestoreMode): Pair<Int, Int> {
        val file = File(backup.dir, Backup.SetupFile)
        return if (file.isFile) prepareSetup(Backup.readSetup(file), mode) else 0 to 0
    }

    private fun prepareSetup(incoming: Setup, mode: RestoreMode): Pair<Int, Int> {
        val liveFile = File(root, Backup.SetupFile)
        val here = try {
            if (liveFile.isFile) Backup.readSetup(liveFile) else null
        } catch (_: IOException) {
            null
        }
        val result = if (mode == RestoreMode.REPLACE || here == null) {
            incoming
        } else {
            RestoreRules.mergeSetup(here, incoming)
        }
        if (result == here) return 0 to 0
        writeSynced(File(newDir, Backup.SetupFile), SetupJson.encode(result))
        val equipment = result.equipment.count { e -> here?.equipment?.none { it.id == e.id } ?: true }
        return equipment to RestoreRules.carsAdded(here, result).size
    }

    /**
     * Coach files and calibrations: merge adds the backup's files that are missing here and keeps
     * the rest (so coach settings here stay unless there are none); replace takes the backup's.
     */
    private fun prepareFiles(backup: UnpackedBackup, area: String, mode: RestoreMode): Int {
        val incoming = Backup.filesUnder(backup.dir, area)
        val target = File(newDir, area)
        if (mode == RestoreMode.REPLACE) {
            target.mkdirs()
            for ((path, f) in incoming) copyFile(f, File(newDir, path))
            return incoming.size
        }
        val missing = incoming.filter { (path, _) -> !File(root, path).exists() }
        if (missing.isNotEmpty()) {
            copyTree(File(root, area), target)
            for ((path, f) in missing) copyFile(f, File(newDir, path))
        }
        return missing.size
    }

    private fun preparePrefs(backup: UnpackedBackup, mode: RestoreMode, here: Map<String, Any>): Boolean {
        val incoming = backup.prefs ?: return false
        val result = if (mode == RestoreMode.REPLACE) incoming else RestoreRules.mergePrefs(here, incoming)
        val changed = result != here
        if (changed) {
            FileOutputStream(prefsFile).use { out ->
                out.write(BackupPrefs.encode(result))
                out.fd.sync()
            }
        }
        return changed
    }

    /** Moves the backup's sessions that are not here into place for the swap. Returns added, kept. */
    private fun prepareSessions(backup: UnpackedBackup): Pair<Int, Int> {
        var added = 0
        var kept = 0
        for (s in File(backup.dir, Backup.Sessions).listFiles().orEmpty()) {
            if (File(File(root, Backup.Sessions), s.name).exists()) {
                kept++
            } else {
                move(s, File(File(newDir, Backup.Sessions), s.name))
                added++
            }
        }
        return added to kept
    }

    /** Swaps the prepared folders in. Runs again after a crash, so each step can be repeated. */
    private fun apply(writePrefs: (Map<String, Any>) -> Unit) {
        for (name in Backup.SwappedAreas) {
            val incoming = File(newDir, name)
            if (!incoming.exists()) continue
            val live = File(root, name)
            val old = File(oldDir, name)
            if (live.exists()) {
                // Both there only if something wrote here after the old copy was moved aside.
                if (old.exists()) live.deleteRecursively() else move(live, old)
            }
            move(incoming, live)
        }
        val sessions = File(root, Backup.Sessions)
        File(newDir, Backup.Sessions).listFiles()?.forEach { s ->
            val target = File(sessions, s.name)
            if (target.exists()) s.deleteRecursively() else move(s, target)
        }
        if (prefsFile.isFile) writePrefs(prefsFile.inputStream().use(::jvmPrefs))
        commitFile.delete()
        SessionFiles.syncDir(work)
        work.deleteRecursively()
    }

    /**
     * Copies the files under [from] to the same place under [to]; files set aside as unreadable go
     * too, so a merge never loses them. Temporary files and locks stay.
     */
    private fun copyTree(from: File, to: File) {
        from.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".lock") }
            .forEach { f -> copyFile(f, File(to, f.relativeTo(from).path)) }
    }

    private fun copyFile(from: File, to: File) {
        to.parentFile.mkdirs()
        from.inputStream().use { input -> SessionBundle.copyTo(to, input) {} }
    }

    private fun syncDirs(dir: File) {
        dir.walkBottomUp().filter { it.isDirectory }.forEach(SessionFiles::syncDir)
    }

    private fun writeSynced(file: File, text: String) {
        file.parentFile.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        SessionFiles.syncDir(file.parentFile)
    }

    private fun move(from: File, to: File) {
        to.parentFile.mkdirs()
        if (!from.renameTo(to)) throw IOException("could not move ${from.path} to ${to.path}")
        SessionFiles.syncDir(to.parentFile)
    }
}
