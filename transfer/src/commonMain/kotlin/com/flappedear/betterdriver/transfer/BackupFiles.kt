package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.SessionFolders
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupFormatException
import com.flappedear.betterdriver.setup.SetupJson
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One file of a backup: its entry path and where it is on this device. */
data class BackupEntry(val path: String, val file: String)

/** How many sessions a backup with "Include sessions" takes, and their bytes without video. */
data class BackupSessionsSize(val count: Int, val bytes: Long)

/**
 * What a backup of the app's data folder [root] holds and how it is written, in common code
 * (FBBD-372) so the iOS app writes the backup the Android app writes (Backup on the JVM) and either
 * restores the other's. The platform only reads files in pieces ([BackupWriter.write]'s readFile).
 */
object BackupContents {
    /** The files a backup of [root] holds, by entry path. */
    fun list(root: String, includeSessions: Boolean): List<BackupEntry> = buildList {
        addAll(filesUnder(root, BackupFormat.Tracks).filter { it.path.endsWith(BackupFormat.TrackSuffix) })
        CoreFiles.join(root, BackupFormat.SetupFile).takeIf { CoreFiles.isFile(it) }
            ?.let { add(BackupEntry(BackupFormat.SetupFile, it)) }
        // A file over its limit would make the whole backup refused on restore, so it stays out.
        addAll(filesUnder(root, BackupFormat.Coach).filter(::fits))
        addAll(filesUnder(root, BackupFormat.Calibrations).filter(::fits))
        if (includeSessions) {
            for (dir in finishedSessions(root)) addAll(sessionFiles(dir))
        }
    }

    /** Sessions a backup with "Include sessions" takes, and their bytes without video. */
    fun sessionsSize(root: String): BackupSessionsSize {
        val sessions = finishedSessions(root)
        val bytes = sessions.sumOf { d -> sessionFiles(d).sumOf { CoreFiles.size(it.file) } }
        return BackupSessionsSize(sessions.size, bytes)
    }

    /** Every file under [root]/[area] that would travel in a bundle, by entry path. */
    fun filesUnder(root: String, area: String): List<BackupEntry> =
        walk(CoreFiles.join(root, area), area)
            .filter { BundleFormat.validPath(it.path) }
            .sortedBy { it.path }

    private fun fits(entry: BackupEntry): Boolean =
        CoreFiles.size(entry.file) <= (BackupFormat.maxBytes(entry.path) ?: Long.MAX_VALUE)

    /** The finished sessions' folders, by id. */
    private fun finishedSessions(root: String): List<String> {
        val folders = SessionFolders(CoreFiles.join(root, BackupFormat.Sessions))
        return folders.list()
            .filter { it.state != SessionState.RECORDING && SessionEntryLimits.validSessionId(it.id) }
            .sortedBy { it.id }
            .map { it.dir }
    }

    /** A session's files as a bundle sends them, without its video folder. */
    private fun sessionFiles(dir: String): List<BackupEntry> {
        val id = dir.trimEnd('/').substringAfterLast('/')
        return CoreFiles.list(dir).sorted().flatMap { name ->
            val full = CoreFiles.join(dir, name)
            when {
                CoreFiles.isDirectory(full) && name == VideoFiles.DIR -> emptyList()
                CoreFiles.isDirectory(full) -> walk(full, "${BackupFormat.Sessions}/$id/$name")
                CoreFiles.isFile(full) && BundleFormat.travels(name) ->
                    listOf(BackupEntry("${BackupFormat.Sessions}/$id/$name", full))
                else -> emptyList()
            }
        }.filter { BundleFormat.validPath(it.path) }.sortedBy { it.path }
    }

    /** The files under folder [dir] that travel, with [prefix] for the folder in their entry paths. */
    internal fun walk(dir: String, prefix: String): List<BackupEntry> = CoreFiles.list(dir).flatMap { name ->
        val full = CoreFiles.join(dir, name)
        when {
            CoreFiles.isDirectory(full) -> walk(full, "$prefix/$name")
            CoreFiles.isFile(full) && BundleFormat.travels(name) -> listOf(BackupEntry("$prefix/$name", full))
            else -> emptyList()
        }
    }
}

/** Writes a backup in one pass, the bytes Android's Backup.write writes. */
object BackupWriter {
    /**
     * Writes a backup of [root] to [out]. [prefs] are the app's preferences, or null for none (the
     * iOS app); [appVersion] is shown when the file is restored. [readFile] hands each piece of the
     * file at a path to its block, with the piece's length. [onProgress] gets the file bytes
     * written and their total.
     */
    @Suppress("LongParameterList") // Each is one part of what goes in.
    fun write(
        root: String,
        includeSessions: Boolean,
        appVersion: String,
        nowUtcMillis: Long,
        prefs: Map<String, Any?>?,
        out: (bytes: ByteArray, from: Int, to: Int) -> Unit,
        readFile: (path: String, block: (ByteArray, Int) -> Unit) -> Unit,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val files = BackupContents.list(root, includeSessions)
        val sizes = files.map { CoreFiles.size(it.file) }
        val total = sizes.sum()
        val packer = BundlePacker(out, BundleFormat.BackupMagic)
        packer.file(BackupFormat.ManifestEntry, BackupFormat.manifest(nowUtcMillis, appVersion, includeSessions))
        prefs?.let { packer.file(BackupFormat.PrefsEntry, BackupPrefs.encode(it)) }
        var written = 0L
        files.forEachIndexed { i, entry ->
            packer.begin(entry.path, sizes[i])
            readFile(entry.file) { bytes, n ->
                packer.bytes(bytes, 0, n)
                written += n
                onProgress(written, total)
            }
            packer.end()
        }
        packer.finish()
    }
}

/**
 * The backup layer of a backup file, fed by a [BundleUnpacker] made with [BundleFormat.BackupMagic]:
 * the manifest first, the preferences, then the data entries ([BackupFormat.isData]) into [files];
 * each entry's declared size is checked against [BackupFormat.maxBytes] and the free space before a
 * byte of it is written. Entries a newer app adds are skipped.
 */
class BackupReceiver(private val files: StagingFiles) : BundleEntrySink {
    private enum class Into { MANIFEST, PREFS, FILE, SKIP }

    private var into = Into.SKIP
    private var small: ByteArray? = null
    private var smallSize = 0
    private var first = true

    /** The manifest, once read. */
    var manifest: Map<String, String>? = null
        private set

    /** The preferences, or null when the backup has none. */
    var prefs: Map<String, Any>? = null
        private set

    override fun begin(path: String, size: Long) {
        if (first) {
            first = false
            if (path != BackupFormat.ManifestEntry || size > BackupFormat.MaxSmallEntry) {
                throw BundleDamagedException("not a backup")
            }
            return startSmall(Into.MANIFEST, size)
        }
        when {
            path == BackupFormat.PrefsEntry -> {
                if (size > BackupFormat.MaxSmallEntry) throw BundleDamagedException("preferences too large")
                startSmall(Into.PREFS, size)
            }
            BackupFormat.isData(path) -> {
                BackupFormat.maxBytes(path)?.let { max ->
                    if (size > max) throw BundleDamagedException("$path is too large ($size bytes)")
                }
                if (size > files.freeBytes() - SessionEntryLimits.SpaceMarginBytes) throw NotEnoughSpaceException(size)
                files.create(path)
                into = Into.FILE
            }
            else -> into = Into.SKIP
        }
    }

    private fun startSmall(kind: Into, size: Long) {
        into = kind
        small = ByteArray(size.toInt())
        smallSize = 0
    }

    override fun bytes(bytes: ByteArray, from: Int, to: Int) {
        when (into) {
            Into.FILE -> files.write(bytes, from, to)
            Into.MANIFEST, Into.PREFS -> {
                bytes.copyInto(small!!, smallSize, from, to)
                smallSize += to - from
            }
            Into.SKIP -> Unit
        }
    }

    override fun end() {
        when (into) {
            Into.MANIFEST -> manifest = BackupFormat.readManifest(small!!)
            Into.PREFS -> prefs = BackupPrefs.decode(small!!)
            Into.FILE -> files.close()
            Into.SKIP -> Unit
        }
        small = null
        into = Into.SKIP
    }
}

/** A backup unpacked into a staging folder and checked, not yet applied; see [BackupRestorer]. */
class StagedBackup internal constructor(
    val summary: BackupSummary,
    internal val dir: String,
    internal val prefs: Map<String, Any>?,
)

/**
 * Restores a backup over the app's data in [root], atomically (FBBD-156), in common code for the iOS
 * app (FBBD-372); it does what Android's BackupRestore does, by path with [CoreFiles].
 *
 * The file is first unpacked into [staging] and checked whole ([unpack]); nothing here changes if it
 * is damaged. [restore] then builds every changed folder in full next to the data (`new/`), writes
 * a commit marker, and only then swaps the folders in with renames, keeping the old ones aside
 * (`old/`) until the end. If the app dies before the marker, the old data stays and the work is
 * dropped ([clearStaging]); after it, the next start finishes the swap ([finishPending]).
 *
 * Sessions are only ever added: one already here is kept, never replaced by a copy without video.
 * [staging] must be on the same volume as [root]. Not thread-safe; one restore at a time.
 */
@Suppress("TooManyFunctions") // One step per part of the data, each small.
class BackupRestorer(private val root: String, private val staging: String) {
    private val work get() = CoreFiles.join(staging, "restore")
    private val newDir get() = CoreFiles.join(work, "new")
    private val oldDir get() = CoreFiles.join(work, "old")
    private val commitFile get() = CoreFiles.join(work, "commit")
    private val prefsFile get() = CoreFiles.join(work, "prefs.properties")

    /**
     * Unpacks and checks a backup. [stagingFiles] makes the platform's writer for a folder;
     * [feedAll] reads the whole file into the feed it is given. On any error the unpacked files are
     * removed and the error passed on ([BundleDamagedException] for a bad file).
     */
    @OptIn(ExperimentalUuidApi::class)
    // Any failure removes the staged files and is passed on; one reason per way a backup can be bad.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun unpack(
        stagingFiles: (dir: String) -> StagingFiles,
        feedAll: (feed: (ByteArray, Int, Int) -> Unit) -> Unit,
    ): StagedBackup {
        val dir = CoreFiles.join(staging, "in-" + Uuid.random().toString())
        if (!CoreFiles.mkdirs(dir)) throw IOException("could not create $dir")
        try {
            val receiver = BackupReceiver(stagingFiles(dir))
            val unpacker = BundleUnpacker(receiver, BundleFormat.BackupMagic)
            feedAll { bytes, from, to -> unpacker.feed(bytes, from, to) }
            unpacker.finish()
            val manifest = receiver.manifest ?: throw BundleDamagedException("empty backup")
            checkCoachSettings(dir)
            return StagedBackup(verify(dir, manifest, receiver.prefs != null), dir, receiver.prefs)
        } catch (e: Throwable) {
            CoreFiles.deleteRecursively(dir)
            throw e
        }
    }

    /** Drops an unpacked backup that will not be restored. */
    fun discard(backup: StagedBackup) {
        CoreFiles.deleteRecursively(backup.dir)
    }

    /**
     * Restores [backup] in [mode]. [prefsHere] are the app's preferences now; [writePrefs] replaces
     * them all with what it is given, and may be called again by [finishPending] (the iOS app has
     * none: both stay empty). [beforeCommit] runs after everything is prepared and before anything
     * changes; it throws to stop (for example when a session started recording meanwhile). The backup
     * is used up either way.
     */
    fun restore(
        backup: StagedBackup,
        mode: RestoreMode,
        prefsHere: Map<String, Any?> = emptyMap(),
        beforeCommit: () -> Unit = {},
        writePrefs: (Map<String, Any>) -> Unit = {},
    ): RestoreResult {
        var committed = false
        try {
            CoreFiles.deleteRecursively(work)
            if (!CoreFiles.mkdirs(newDir)) throw IOException("could not prepare the restore")
            val result = prepare(backup, mode, BackupPrefs.kept(prefsHere))
            beforeCommit()
            CoreFiles.writeTextAtomically(commitFile, mode.name)
            committed = true
            apply(writePrefs)
            return result
        } finally {
            if (!committed) CoreFiles.deleteRecursively(work)
            discard(backup)
        }
    }

    /**
     * Finishes a restore the app was killed in after its commit marker. Call at start, before
     * anything reads the data. Quick: renames only. Returns whether one was finished.
     */
    fun finishPending(writePrefs: (Map<String, Any>) -> Unit = {}): Boolean {
        if (!CoreFiles.isFile(commitFile)) return false
        apply(writePrefs)
        return true
    }

    /** Whether a committed restore is still to be finished ([finishPending]). */
    val pending: Boolean get() = CoreFiles.isFile(commitFile)

    /** Removes what an unfinished check or restore left behind. Not while one runs. */
    fun clearStaging() {
        if (!CoreFiles.isFile(commitFile)) CoreFiles.deleteRecursively(staging)
    }

    private fun prepare(backup: StagedBackup, mode: RestoreMode, prefsHere: Map<String, Any>): RestoreResult {
        val (tracksAdded, tracksUpdated) = prepareTracks(backup, mode)
        val (equipmentAdded, carsAdded) = prepareSetup(backup, mode)
        val files = prepareFiles(backup, BackupFormat.Coach, mode) +
            prepareFiles(backup, BackupFormat.Calibrations, mode)
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
    private fun prepareTracks(backup: StagedBackup, mode: RestoreMode): Pair<Int, Int> {
        val live = CoreFiles.join(root, BackupFormat.Tracks)
        val target = CoreFiles.join(newDir, BackupFormat.Tracks)
        CoreFiles.mkdirs(target)
        if (mode == RestoreMode.MERGE) copyTree(live, target)
        var added = 0
        var updated = 0
        val incomingDir = CoreFiles.join(backup.dir, BackupFormat.Tracks)
        for (name in CoreFiles.list(incomingDir).sorted()) {
            val f = CoreFiles.join(incomingDir, name)
            val incoming = readTrack(f)
            val here = trackHere(CoreFiles.join(live, name))
            val take = mode == RestoreMode.REPLACE || here == null || RestoreRules.newer(incoming, here)
            if (!take) continue
            copyFile(f, CoreFiles.join(target, name))
            when {
                here == null -> added++
                trackMatch(incoming, here) != TrackMatch.SAME -> updated++
            }
        }
        // Nothing to swap when a merge changes nothing.
        if (mode == RestoreMode.MERGE && added + updated == 0) CoreFiles.deleteRecursively(target)
        return added to updated
    }

    /** A track here, or null when there is none or it cannot be read (the backup's then wins). */
    private fun trackHere(file: String): Track? = try {
        if (CoreFiles.isFile(file)) readTrack(file) else null
    } catch (_: BundleDamagedException) {
        null
    } catch (_: IOException) {
        null
    }

    /** Equipment and cars. Returns how many of each are new. */
    private fun prepareSetup(backup: StagedBackup, mode: RestoreMode): Pair<Int, Int> {
        val file = CoreFiles.join(backup.dir, BackupFormat.SetupFile)
        return if (CoreFiles.isFile(file)) prepareSetup(readSetup(file), mode) else 0 to 0
    }

    private fun prepareSetup(incoming: Setup, mode: RestoreMode): Pair<Int, Int> {
        val liveFile = CoreFiles.join(root, BackupFormat.SetupFile)
        val here = try {
            if (CoreFiles.isFile(liveFile)) readSetup(liveFile) else null
        } catch (_: BundleDamagedException) {
            null
        } catch (_: IOException) {
            null
        }
        val result = if (mode == RestoreMode.REPLACE || here == null) {
            incoming
        } else {
            RestoreRules.mergeSetup(here, incoming)
        }
        if (result == here) return 0 to 0
        CoreFiles.writeTextAtomically(CoreFiles.join(newDir, BackupFormat.SetupFile), SetupJson.encode(result))
        val equipment = result.equipment.count { e -> here?.equipment?.none { it.id == e.id } ?: true }
        return equipment to RestoreRules.carsAdded(here, result).size
    }

    /**
     * Coach files and calibrations: merge adds the backup's files that are missing here and keeps
     * the rest (so coach settings here stay unless there are none); replace takes the backup's.
     */
    private fun prepareFiles(backup: StagedBackup, area: String, mode: RestoreMode): Int {
        val incoming = BackupContents.filesUnder(backup.dir, area)
        val target = CoreFiles.join(newDir, area)
        if (mode == RestoreMode.REPLACE) {
            CoreFiles.mkdirs(target)
            for (e in incoming) copyFile(e.file, CoreFiles.join(newDir, e.path))
            return incoming.size
        }
        val missing = incoming.filter { !CoreFiles.exists(CoreFiles.join(root, it.path)) }
        if (missing.isNotEmpty()) {
            copyTree(CoreFiles.join(root, area), target)
            for (e in missing) copyFile(e.file, CoreFiles.join(newDir, e.path))
        }
        return missing.size
    }

    private fun preparePrefs(backup: StagedBackup, mode: RestoreMode, here: Map<String, Any>): Boolean {
        val incoming = backup.prefs ?: return false
        val result = if (mode == RestoreMode.REPLACE) incoming else RestoreRules.mergePrefs(here, incoming)
        val changed = result != here
        if (changed) CoreFiles.writeBytesAtomically(prefsFile, BackupPrefs.encode(result))
        return changed
    }

    /** Moves the backup's sessions that are not here into place for the swap. Returns added, kept. */
    private fun prepareSessions(backup: StagedBackup): Pair<Int, Int> {
        var added = 0
        var kept = 0
        val incoming = CoreFiles.join(backup.dir, BackupFormat.Sessions)
        for (id in CoreFiles.list(incoming).sorted()) {
            if (CoreFiles.exists(CoreFiles.join(CoreFiles.join(root, BackupFormat.Sessions), id))) {
                kept++
            } else {
                move(CoreFiles.join(incoming, id), CoreFiles.join(CoreFiles.join(newDir, BackupFormat.Sessions), id))
                added++
            }
        }
        return added to kept
    }

    /** Swaps the prepared folders in. Runs again after a crash, so each step can be repeated. */
    private fun apply(writePrefs: (Map<String, Any>) -> Unit) {
        for (name in BackupFormat.SwappedAreas) {
            val incoming = CoreFiles.join(newDir, name)
            if (!CoreFiles.exists(incoming)) continue
            val live = CoreFiles.join(root, name)
            val old = CoreFiles.join(oldDir, name)
            if (CoreFiles.exists(live)) {
                // Both there only if something wrote here after the old copy was moved aside.
                if (CoreFiles.exists(old)) CoreFiles.deleteRecursively(live) else move(live, old)
            }
            move(incoming, live)
        }
        val sessions = CoreFiles.join(root, BackupFormat.Sessions)
        val newSessions = CoreFiles.join(newDir, BackupFormat.Sessions)
        for (id in CoreFiles.list(newSessions)) {
            val s = CoreFiles.join(newSessions, id)
            val target = CoreFiles.join(sessions, id)
            if (CoreFiles.exists(target)) CoreFiles.deleteRecursively(s) else move(s, target)
        }
        if (CoreFiles.isFile(prefsFile)) writePrefs(BackupPrefs.decode(CoreFiles.readBytes(prefsFile)))
        CoreFiles.delete(commitFile)
        CoreFiles.deleteRecursively(work)
    }

    /**
     * Copies the files under [from] to the same place under [to]; files set aside as unreadable go
     * too, so a merge never loses them. Temporary files and locks stay.
     */
    private fun copyTree(from: String, to: String) {
        for (name in CoreFiles.list(from)) {
            val f = CoreFiles.join(from, name)
            when {
                CoreFiles.isDirectory(f) -> copyTree(f, CoreFiles.join(to, name))
                CoreFiles.isFile(f) && !name.endsWith(".tmp") && !name.endsWith(".lock") ->
                    copyFile(f, CoreFiles.join(to, name))
            }
        }
    }

    /** Files copied here are all bounded and read whole ([BackupFormat.maxBytes]). */
    private fun copyFile(from: String, to: String) {
        CoreFiles.writeBytesAtomically(to, CoreFiles.readBytes(from))
    }

    private fun move(from: String, to: String) {
        CoreFiles.mkdirs(to.substringBeforeLast('/'))
        if (!CoreFiles.move(from, to)) throw IOException("could not move $from to $to")
    }

    // One reason per way a backup can be bad, one check per part of it.
    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    private fun verify(dir: String, m: Map<String, String>, prefs: Boolean): BackupSummary {
        val created = m["created"]?.toLongOrNull() ?: throw BundleDamagedException("backup has no date")
        val tracksDir = CoreFiles.join(dir, BackupFormat.Tracks)
        val tracks = CoreFiles.list(tracksDir)
        for (name in tracks) {
            val f = CoreFiles.join(tracksDir, name)
            val track = if (CoreFiles.isFile(f) && name.endsWith(BackupFormat.TrackSuffix)) readTrack(f) else null
            if (track == null || track.id + BackupFormat.TrackSuffix != name || !BackupFormat.validTrackId(track.id)) {
                throw BundleDamagedException("bad track file $name")
            }
        }
        val setupFile = CoreFiles.join(dir, BackupFormat.SetupFile)
        val setup = if (CoreFiles.isFile(setupFile)) readSetup(setupFile) else null
        val sessionsDir = CoreFiles.join(dir, BackupFormat.Sessions)
        val sessions = CoreFiles.list(sessionsDir)
        for (id in sessions) {
            val s = CoreFiles.join(sessionsDir, id)
            if (!CoreFiles.isDirectory(s) || !SessionEntryLimits.validSessionId(id)) {
                throw BundleDamagedException("bad session $id")
            }
            val meta = CoreFiles.join(s, SessionFileNames.META)
            if (!CoreFiles.isFile(meta)) throw BundleDamagedException("session $id has no metadata")
            checkedSessionInfo(CoreFiles.readBytes(meta), "session $id")
        }
        return BackupSummary(
            createdUtcMillis = created,
            appVersion = m["app"].orEmpty(),
            tracks = tracks.size,
            equipment = setup?.equipment?.size ?: 0,
            cars = setup?.cars?.size ?: 0,
            sessions = sessions.size,
            calibrations = BackupContents.filesUnder(dir, BackupFormat.Calibrations).size,
            coachFiles = BackupContents.filesUnder(dir, BackupFormat.Coach).size,
            prefs = prefs,
            withSessions = m["sessions"].toBoolean(),
        )
    }

    /** The coach settings the app reads first after a restore are a readable properties file. */
    private fun checkCoachSettings(dir: String) {
        val file = CoreFiles.join(CoreFiles.join(dir, BackupFormat.Coach), BackupFormat.CoachSettingsFile)
        if (!CoreFiles.isFile(file)) return
        try {
            parseProperties(CoreFiles.readBytes(file))
        } catch (e: IllegalArgumentException) {
            throw BundleDamagedException("bad coach settings: ${e.message}", e)
        }
    }

    private fun readTrack(file: String): Track = try {
        TrackJson.decode(CoreFiles.readText(file))
    } catch (e: TrackFormatException) {
        throw BundleDamagedException("bad track ${file.substringAfterLast('/')}: ${e.message}", e)
    }

    private fun readSetup(file: String): Setup = try {
        SetupJson.decode(CoreFiles.readText(file))
    } catch (e: SetupFormatException) {
        throw BundleDamagedException("bad equipment and cars: ${e.message}", e)
    }
}
