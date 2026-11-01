package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupFormatException
import com.flappedear.betterdriver.setup.SetupJson
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.UUID

/** A backup unpacked into a staging folder and checked, not yet applied; see [BackupRestore]. */
class UnpackedBackup internal constructor(
    val summary: BackupSummary,
    internal val dir: File,
    internal val prefs: Map<String, Any>?,
)

/**
 * The app's own data as one file (FBBD-156), RaceChrono's backup for BetterDriver. It uses the
 * session bundle's container ([BundleFile], with a CRC per entry) under its own magic. Entries, in
 * order:
 *
 * ```
 * manifest.properties    format, version, when, app version, whether sessions are in
 * prefs/app.properties   the app's preferences, one "type:value" per key
 * tracks/<id>.track.json saved tracks, as TrackStore keeps them
 * setup.json             equipment, cars and the next session's choice (SetupStore)
 * coach/...              coach settings, references and picks
 * imu-calibration/...    RaceBox motion-sensor calibrations
 * sessions/<id>/...      with "Include sessions": each session's data files, without its video
 * ```
 *
 * Paths are those under the app's files folder, so a restore puts each entry back where it was.
 * Nothing else goes in: no debug logs, no update state, no map tiles or circuit cache, no secrets.
 */
@Suppress("TooManyFunctions") // Writing, reading and checking one format, in small steps.
object Backup {
    internal val Magic = BundleFormat.BackupMagic
    // The format is shared with the iOS app (BackupFormat, FBBD-372); these are its names here.
    internal const val Format = BackupFormat.Format
    internal const val Version = BackupFormat.Version
    internal const val ManifestEntry = BackupFormat.ManifestEntry
    internal const val PrefsEntry = BackupFormat.PrefsEntry

    internal const val Tracks = BackupFormat.Tracks
    internal const val SetupFile = BackupFormat.SetupFile
    internal const val Coach = BackupFormat.Coach
    private const val CoachSettingsFile = BackupFormat.CoachSettingsFile
    internal const val Calibrations = BackupFormat.Calibrations
    internal const val Sessions = BackupFormat.Sessions

    /** As TrackStore names its files. */
    internal const val TrackSuffix = BackupFormat.TrackSuffix

    /** Folders and files a restore swaps whole; sessions are added one by one. */
    internal val SwappedAreas = BackupFormat.SwappedAreas
    private const val MaxSmallEntry = BackupFormat.MaxSmallEntry
    internal const val MaxTrackBytes = BackupFormat.MaxTrackBytes
    internal const val MaxSetupBytes = BackupFormat.MaxSetupBytes
    internal const val MaxMetaBytes = SessionLimits.MaxMetaBytes

    /** Coach settings and cue counts (properties), read whole at every start (audit BD-03). */
    internal const val MaxCoachPropertiesBytes = BackupFormat.MaxCoachPropertiesBytes

    /** The coach's other files (references, picks, line and day plans, history), each read whole. */
    internal const val MaxCoachFileBytes = BackupFormat.MaxCoachFileBytes

    /** A RaceBox motion-sensor calibration: a few numbers. */
    internal const val MaxCalibrationBytes = BackupFormat.MaxCalibrationBytes

    /** The files a backup of [root] (the app's files folder) holds, by entry path. */
    fun contents(root: File, includeSessions: Boolean): List<Pair<String, File>> = buildList {
        addAll(filesUnder(root, Tracks).filter { it.first.endsWith(TrackSuffix) })
        File(root, SetupFile).takeIf { it.isFile }?.let { add(SetupFile to it) }
        // A file over its limit would make the whole backup refused on restore, so it stays out.
        addAll(filesUnder(root, Coach).filter(::fits))
        addAll(filesUnder(root, Calibrations).filter(::fits))
        if (includeSessions) {
            for (s in finishedSessions(root)) addAll(sessionFiles(s.dir))
        }
    }

    /** Sessions a backup with "Include sessions" takes, and their bytes without video. */
    fun sessionsSize(root: File): Pair<Int, Long> {
        val sessions = finishedSessions(root)
        return sessions.size to sessions.sumOf { s -> sessionFiles(s.dir).sumOf { it.second.length() } }
    }

    /** Bytes [write] copies from files, for progress. */
    fun size(root: File, includeSessions: Boolean): Long = contents(root, includeSessions).sumOf { it.second.length() }

    /**
     * Writes a backup of [root] to [out]. [prefs] are the app's preferences (only booleans, numbers
     * and strings are kept); [appVersion] is shown when the file is restored. [onBytes] is told about
     * each block of file bytes written.
     */
    @Suppress("LongParameterList") // Each is one part of what goes in.
    fun write(
        root: File,
        prefs: Map<String, Any?>,
        appVersion: String,
        includeSessions: Boolean,
        out: OutputStream,
        nowUtcMillis: Long = System.currentTimeMillis(),
        onBytes: (Int) -> Unit = {},
    ) {
        val files = contents(root, includeSessions)
        val writer = BundleWriter(out, Magic)
        val manifest = Properties().apply {
            setProperty("format", Format)
            setProperty("version", Version.toString())
            setProperty("created", nowUtcMillis.toString())
            setProperty("app", appVersion)
            setProperty("sessions", includeSessions.toString())
        }
        small(writer, ManifestEntry, ByteArrayOutputStream().also { manifest.store(it, null) }.toByteArray())
        small(writer, PrefsEntry, BackupPrefs.encode(prefs))
        for ((path, file) in files) {
            file.inputStream().use { writer.file(path, file.length(), it, onBytes) }
        }
        writer.finish()
        out.flush()
    }

    private fun small(writer: BundleWriter, path: String, bytes: ByteArray) =
        writer.file(path, bytes.size.toLong(), bytes.inputStream())

    /**
     * Unpacks a backup from [input] into a new folder under [staging] and checks all of it: every
     * entry's CRC, the manifest, each track, the setup and each session's metadata. Nothing of the
     * app's data is touched. On any error the folder is removed and [BundleFormatException] (or
     * [IOException]) is thrown.
     */
    @Suppress("ThrowsCount") // One reason per way a backup can be bad.
    fun unpack(input: InputStream, staging: File, onBytes: (Int) -> Unit = {}): UnpackedBackup {
        val dir = File(staging, "in-" + UUID.randomUUID())
        check(dir.mkdirs()) { "could not create ${dir.path}" }
        try {
            var manifest: Properties? = null
            var prefs: Map<String, Any>? = null
            BundleReader(input, Magic).read { path, size, contents ->
                when {
                    manifest == null -> {
                        manifest = readManifest(path, size, contents)
                    }
                    path == PrefsEntry -> {
                        if (size > MaxSmallEntry) throw BundleFormatException("preferences too large")
                        prefs = jvmPrefs(contents)
                    }
                    BackupFormat.isData(path) -> {
                        checkSize(path, size)
                        SessionBundle.copyTo(File(dir, path), contents, onBytes)
                    }
                    else -> Unit // Entries a newer app adds are skipped.
                }
            }
            val m = manifest ?: throw BundleFormatException("empty backup")
            checkCoachSettings(dir)
            return UnpackedBackup(verify(dir, m, prefs != null), dir, prefs)
        } catch (e: IOException) {
            dir.deleteRecursively()
            throw e
        } catch (e: IllegalArgumentException) {
            dir.deleteRecursively()
            throw BundleFormatException("damaged backup: ${e.message}", e)
        }
    }

    /**
     * Tracks, the setup, the coach's files, calibrations and session files read whole (metadata,
     * chunks, indexes; [SessionLimits]) are bounded, checked from the declared size before the entry
     * is copied.
     */
    private fun checkSize(path: String, size: Long) {
        val max = maxBytes(path) ?: return
        if (size > max) throw BundleFormatException("$path is too large ($size bytes)")
    }

    /** The most an entry at [path] may hold; null for one the app streams (session video is never in). */
    internal fun maxBytes(path: String): Long? = BackupFormat.maxBytes(path)

    private fun fits(entry: Pair<String, File>): Boolean =
        entry.second.length() <= (maxBytes(entry.first) ?: Long.MAX_VALUE)

    /** The coach settings the app reads first after a restore are a readable properties file. */
    private fun checkCoachSettings(dir: File) {
        val file = File(dir, "$Coach/$CoachSettingsFile").takeIf { it.isFile } ?: return
        try {
            file.inputStream().use { Properties().load(it) }
        } catch (e: IllegalArgumentException) {
            throw BundleFormatException("bad coach settings: ${e.message}", e)
        }
    }

    @Suppress("ThrowsCount") // One reason per way a manifest can be bad.
    private fun readManifest(path: String, size: Long, contents: InputStream): Properties {
        if (path != ManifestEntry || size > MaxSmallEntry) throw BundleFormatException("not a backup")
        val m = Properties().apply { load(contents) }
        if (m.getProperty("format") != Format) throw BundleFormatException("not a BetterDriver backup")
        val version = m.getProperty("version")?.toIntOrNull() ?: throw BundleFormatException("backup has no version")
        if (version > Version) throw BundleFormatException("made by a newer app (backup version $version)")
        return m
    }

    @Suppress("ThrowsCount") // One reason per way a backup can be bad.
    private fun verify(dir: File, m: Properties, prefs: Boolean): BackupSummary {
        val created = m.getProperty("created")?.toLongOrNull() ?: throw BundleFormatException("backup has no date")
        val tracks = File(dir, Tracks).listFiles().orEmpty()
        for (f in tracks) {
            val track = if (f.isFile && f.name.endsWith(TrackSuffix)) readTrack(f) else null
            if (track == null || track.id + TrackSuffix != f.name || !validTrackId(track.id)) {
                throw BundleFormatException("bad track file ${f.name}")
            }
        }
        val setup = File(dir, SetupFile).takeIf { it.isFile }?.let(::readSetup)
        val sessions = File(dir, Sessions).listFiles().orEmpty()
        for (s in sessions) {
            if (!s.isDirectory || !SessionInbox.validId(s.name)) throw BundleFormatException("bad session ${s.name}")
            readInfo(s)
        }
        return BackupSummary(
            createdUtcMillis = created,
            appVersion = m.getProperty("app").orEmpty(),
            tracks = tracks.size,
            equipment = setup?.equipment?.size ?: 0,
            cars = setup?.cars?.size ?: 0,
            sessions = sessions.size,
            calibrations = filesUnder(dir, Calibrations).size,
            coachFiles = filesUnder(dir, Coach).size,
            prefs = prefs,
            withSessions = m.getProperty("sessions").toBoolean(),
        )
    }

    internal fun readTrack(file: File): Track = try {
        TrackJson.decode(file.readText())
    } catch (e: TrackFormatException) {
        throw BundleFormatException("bad track ${file.name}: ${e.message}", e)
    }

    internal fun readSetup(file: File): Setup = try {
        SetupJson.decode(file.readText())
    } catch (e: SetupFormatException) {
        throw BundleFormatException("bad equipment and cars: ${e.message}", e)
    }

    private fun readInfo(sessionDir: File) {
        SessionLimits.readInfo(sessionDir, "session ${sessionDir.name}")
    }

    /** As TrackStore accepts them. */
    private fun validTrackId(id: String) = BackupFormat.validTrackId(id)

    /** Every file under [root]/[area] that would travel in a bundle, by entry path. */
    internal fun filesUnder(root: File, area: String): List<Pair<String, File>> {
        val dir = File(root, area)
        return dir.walkTopDown()
            .filter { it.isFile && SessionBundle.travels(it.name) }
            .map { "$area/" + it.relativeTo(dir).invariantSeparatorsPath to it }
            .filter { BundleFile.validPath(it.first) }
            .sortedBy { it.first }
            .toList()
    }

    private fun finishedSessions(root: File) = SessionStore(File(root, Sessions)).list()
        .filter { it.state != SessionState.RECORDING && SessionInbox.validId(it.id) }
        .sortedBy { it.id }

    /** A session's files as [SessionBundle] sends them, without its video folder. */
    private fun sessionFiles(sessionDir: File): List<Pair<String, File>> =
        sessionDir.walkTopDown()
            .onEnter { it == sessionDir || it.parentFile != sessionDir || it.name != VideoFiles.DIR }
            .filter { it.isFile && SessionBundle.travels(it.name) }
            .map { "$Sessions/${sessionDir.name}/" + it.relativeTo(sessionDir).invariantSeparatorsPath to it }
            .filter { BundleFile.validPath(it.first) }
            .sortedBy { it.first }
            .toList()
}

/** The backup's preferences (shared [BackupPrefs]) from [input], refused as the JVM reader refuses a bad entry. */
internal fun jvmPrefs(input: InputStream): Map<String, Any> = try {
    BackupPrefs.decode(input.readBytes())
} catch (e: BundleDamagedException) {
    throw BundleFormatException(e.message ?: "bad preferences", e)
}
