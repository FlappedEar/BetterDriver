package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.timing.track.Track

/** File name extension of a backup (FBBD-156). */
const val BackupExtension = "bdbackup"

/** What a backup holds, read from the file itself after every entry was checked. */
data class BackupSummary(
    val createdUtcMillis: Long,
    /** The version of the app that wrote it, as it showed it in Settings. */
    val appVersion: String,
    val tracks: Int,
    val equipment: Int,
    val cars: Int,
    val sessions: Int,
    val calibrations: Int,
    /** Coach settings, references and the rest of the coach's files. */
    val coachFiles: Int,
    /** Whether it holds the app's preferences. */
    val prefs: Boolean,
    /** Whether "Include sessions" was on when it was written. */
    val withSessions: Boolean,
)

/** How a backup is brought back (FBBD-156). */
enum class RestoreMode {
    /** Keeps what is here and adds from the backup: newer tracks, missing equipment, cars and sessions. */
    MERGE,

    /** Tracks, equipment and cars, settings, coach files and calibrations become the backup's. */
    REPLACE,
}

/** What a restore changed, for the message after it. */
data class RestoreResult(
    val tracksAdded: Int = 0,
    val tracksUpdated: Int = 0,
    val equipmentAdded: Int = 0,
    val carsAdded: Int = 0,
    val sessionsAdded: Int = 0,
    /** Sessions in the backup that were already here: those here are kept. */
    val sessionsKept: Int = 0,
    /** Coach and calibration files added or replaced. */
    val filesRestored: Int = 0,
    val prefsChanged: Boolean = false,
)

/**
 * The backup file (FBBD-156), shared by the Android app and the iOS app (FBBD-372) so a backup made
 * on one restores on the other. It is the session bundle's container ([BundlePacker],
 * [BundleUnpacker], a CRC per entry) under the magic [BundleFormat.BackupMagic]. Entries, in order:
 *
 * ```
 * manifest.properties    format, version, when, app version, whether sessions are in
 * prefs/app.properties   the app's preferences, one "type:value" per key (Android only)
 * tracks/<id>.track.json saved tracks, as TrackStore keeps them
 * setup.json             equipment, cars and the next session's choice (SetupStore)
 * coach/...              coach settings, references and picks
 * imu-calibration/...    RaceBox motion-sensor calibrations
 * sessions/<id>/...      with "Include sessions": each session's data files, without its video
 * ```
 *
 * Paths are those under the app's files folder (Android) or Application Support (iOS), the same
 * layout on both, so a restore puts each entry back where it was. Nothing else goes in: no debug
 * logs, no update state, no map tiles or circuit cache, no secrets.
 */
object BackupFormat {
    const val Format = "betterdriver-backup"
    const val Version = 1
    const val ManifestEntry = "manifest.properties"
    const val PrefsEntry = "prefs/app.properties"

    const val Tracks = "tracks"
    const val SetupFile = "setup.json"
    const val Coach = "coach"
    const val CoachSettingsFile = "settings.properties"
    const val Calibrations = "imu-calibration"
    const val Sessions = "sessions"

    /** As TrackStore names its files. */
    const val TrackSuffix = ".track.json"

    /** Folders and files a restore swaps whole; sessions are added one by one. */
    val SwappedAreas = listOf(Tracks, SetupFile, Coach, Calibrations)

    /** Folders whose files a backup carries. */
    val DirAreas = listOf(Tracks, Coach, Calibrations, Sessions)

    /** The manifest and the preferences. */
    const val MaxSmallEntry = 4L shl 20
    const val MaxTrackBytes = 1L shl 20
    const val MaxSetupBytes = 4L shl 20

    /** Coach settings and cue counts (properties), read whole at every start (audit BD-03). */
    const val MaxCoachPropertiesBytes = 1L shl 20

    /** The coach's other files (references, picks, line and day plans, history), each read whole. */
    const val MaxCoachFileBytes = 8L shl 20

    /** A RaceBox motion-sensor calibration: a few numbers. */
    const val MaxCalibrationBytes = 64L shl 10

    /** The most an entry at [path] may hold; null for one the app streams (session video is never in). */
    fun maxBytes(path: String): Long? = when {
        path == SetupFile -> MaxSetupBytes
        path.startsWith("$Tracks/") -> MaxTrackBytes
        path.startsWith("$Coach/") && path.endsWith(".properties") -> MaxCoachPropertiesBytes
        path.startsWith("$Coach/") -> MaxCoachFileBytes
        path.startsWith("$Calibrations/") -> MaxCalibrationBytes
        path.startsWith("$Sessions/") -> SessionEntryLimits.maxBytes(path.substringAfter('/').substringAfter('/'))
        else -> null
    }

    /** Whether an entry at [path] is part of the app's data a restore brings back. */
    fun isData(path: String): Boolean = path == SetupFile || DirAreas.any { path.startsWith("$it/") }

    /** The manifest's bytes. */
    fun manifest(nowUtcMillis: Long, appVersion: String, includeSessions: Boolean): ByteArray = writeProperties(
        linkedMapOf(
            "format" to Format,
            "version" to Version.toString(),
            "created" to nowUtcMillis.toString(),
            "app" to appVersion,
            "sessions" to includeSessions.toString(),
        ),
    )

    /** The manifest entry, checked: throws [BundleDamagedException] when it is not one this app reads. */
    @Suppress("ThrowsCount") // One reason per way a manifest can be bad.
    fun readManifest(bytes: ByteArray): Map<String, String> {
        val m = try {
            parseProperties(bytes)
        } catch (e: IllegalArgumentException) {
            throw BundleDamagedException("not a backup", e)
        }
        if (m["format"] != Format) throw BundleDamagedException("not a BetterDriver backup")
        val version = m["version"]?.toIntOrNull() ?: throw BundleDamagedException("backup has no version")
        if (version > Version) throw BundleDamagedException("made by a newer app (backup version $version)")
        return m
    }

    /** As TrackStore accepts them. */
    fun validTrackId(id: String) = id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "-_" }
}

/**
 * The app's preferences in a backup: a properties file with "b:", "i:", "l:", "f:" or "s:" before
 * each value for its type. Other types (string sets) are not kept; a type a newer app adds is skipped.
 * The iOS app keeps no preferences of the Android app's, so its backups have none and it ignores them.
 */
object BackupPrefs {
    fun encode(prefs: Map<String, Any?>): ByteArray {
        val p = LinkedHashMap<String, String>()
        for ((key, value) in prefs) {
            val text = when (value) {
                is Boolean -> "b:$value"
                is Int -> "i:$value"
                is Long -> "l:$value"
                is Float -> "f:$value"
                is String -> "s:$value"
                else -> null
            }
            text?.let { p[key] = it }
        }
        return writeProperties(p)
    }

    fun decode(bytes: ByteArray): Map<String, Any> {
        val p = try {
            parseProperties(bytes)
        } catch (e: IllegalArgumentException) {
            throw BundleDamagedException("bad preferences: ${e.message}", e)
        }
        return p.mapNotNull { (key, text) -> value(text)?.let { key to it } }.toMap()
    }

    /** [prefs] as a backup would bring them back. */
    fun kept(prefs: Map<String, Any?>): Map<String, Any> = decode(encode(prefs))

    private fun value(text: String): Any? {
        val v = text.substringAfter(':')
        return when (text.substringBefore(':', "")) {
            "b" -> v.toBooleanStrictOrNull() ?: bad(text)
            "i" -> v.toIntOrNull() ?: bad(text)
            "l" -> v.toLongOrNull() ?: bad(text)
            "f" -> v.toFloatOrNull() ?: bad(text)
            "s" -> v
            else -> null
        }
    }

    private fun bad(text: String): Nothing = throw BundleDamagedException("bad preference value $text")
}

/** The merge rules, on their own so they are easy to test. */
object RestoreRules {
    /** A track from the backup wins over the one here when its revision, then its update time, is higher. */
    fun newer(incoming: Track, here: Track): Boolean = incoming.revision > here.revision ||
        incoming.revision == here.revision && incoming.updatedUtcMillis > here.updatedUtcMillis

    /**
     * The setup here with the backup's equipment and cars that are missing added. An item is
     * already here when it has the same id, or is the same device: same model and address, or a
     * model listed once at most (the phone camera, which a fresh install already lists). The next
     * session's choice here is kept unless no receiver or car was chosen; ids that no longer resolve
     * drop out.
     */
    fun mergeSetup(here: Setup, incoming: Setup): Setup {
        val sameAs = HashMap<String, String>() // backup id -> id here
        val added = incoming.equipment.filter { e ->
            val match = here.equipment.firstOrNull { it.id == e.id || sameDevice(it, e) }
            if (match != null) sameAs[e.id] = match.id
            match == null
        }
        val cars = here.cars + incoming.cars.filter { c -> here.cars.none { it.id == c.id } }
        val equipment = here.equipment + added
        // A fresh install has the phone camera on and nothing else chosen.
        val chosen = here.session.gnssId != null || here.session.carId != null
        val session = if (chosen) {
            here.session
        } else {
            incoming.session.copy(
                gnssId = incoming.session.gnssId?.let { sameAs[it] ?: it },
                cameraIds = incoming.session.cameraIds.map { sameAs[it] ?: it }.distinct(),
            )
        }
        return Setup(
            equipment = equipment,
            cars = cars,
            session = session.copy(
                carId = session.carId?.takeIf { id -> cars.any { it.id == id } },
                gnssId = session.gnssId?.takeIf { id -> equipment.any { it.id == id } },
                cameraIds = session.cameraIds.filter { id -> equipment.any { it.id == id } },
            ),
        )
    }

    /** As Setup.addEquipment matches a device already listed. */
    private fun sameDevice(a: Equipment, b: Equipment) =
        a.model == b.model && (a.model.single || a.address != null && a.address == b.address)

    /** Preferences here win; the backup only fills in what is absent. */
    fun mergePrefs(here: Map<String, Any>, incoming: Map<String, Any>): Map<String, Any> = incoming + here

    internal fun carsAdded(here: Setup?, result: Setup): List<CarProfile> =
        result.cars.filter { c -> here?.cars?.none { it.id == c.id } ?: true }
}

/** The words of Back up and Restore, the same on Android and iOS (FBBD-372). */
object BackupTexts {
    private const val BytesPerMb = 1_000_000.0

    /** What a backup takes, at the top of the Back up dialog. */
    const val WhatGoesIn = "Tracks, equipment and cars, app and coach settings, coach references and sensor " +
        "calibrations go into one file. Keep it off the phone, on Drive or a computer."

    /** Under the restore sheet's rows: what Merge and Replace do. */
    const val MergeOrReplace = "Merge keeps everything on this device and adds what it lacks: newer tracks, other " +
        "equipment, cars and sessions, and settings not set here. Replace makes tracks, " +
        "equipment, cars and settings the backup's. Sessions here are always kept."

    /** The second question before Replace. */
    const val ReplaceWarning = "Tracks, equipment and cars, app and coach settings, coach references and sensor " +
        "calibrations on this device are replaced by the backup's. Sessions here are kept. " +
        "This cannot be undone."

    /** File name offered for a new backup, with the local date [isoDate] (2026-10-12). */
    fun fileName(isoDate: String): String = "BetterDriver-backup-$isoDate.$BackupExtension"

    /** Under "Include sessions" in the backup dialog: how many and how big, or "Counting…" while [count] is null. */
    fun sessionsOption(count: Int?, bytes: Long): String = when {
        count == null -> "Counting…"
        count == 0 -> "No sessions yet"
        else -> plural(count, "session") + ", " + fixed(bytes / BytesPerMb, 1) + " MB"
    }

    /** When the backup was made ([at], formatted by the platform) and by which version. */
    fun made(at: String, appVersion: String): String =
        if (appVersion.isBlank()) "Made $at" else "Made $at with BetterDriver $appVersion"

    /** What the backup holds, as label and value rows for the restore sheet. */
    fun contentRows(s: BackupSummary): List<Pair<String, String>> = listOf(
        "Tracks" to s.tracks.toString(),
        "Equipment" to s.equipment.toString(),
        "Cars" to s.cars.toString(),
        "Sessions" to if (s.withSessions) "${s.sessions}, without video" else "Not included",
        "Coach settings and references" to if (s.coachFiles == 0) "None" else plural(s.coachFiles, "file"),
        "Sensor calibrations" to s.calibrations.toString(),
        "App settings" to if (s.prefs) "Included" else "None",
    )

    /** The labels of [contentRows], for Swift. */
    fun contentLabels(s: BackupSummary): List<String> = contentRows(s).map { it.first }

    /** The values of [contentRows], for Swift. */
    fun contentValues(s: BackupSummary): List<String> = contentRows(s).map { it.second }

    /** The line under Back up and Restore after a restore. */
    fun result(r: RestoreResult): String {
        val parts = buildList {
            if (r.tracksAdded > 0) add(plural(r.tracksAdded, "track") + " added")
            if (r.tracksUpdated > 0) add(plural(r.tracksUpdated, "track") + " updated")
            if (r.equipmentAdded > 0) add(plural(r.equipmentAdded, "device") + " added")
            if (r.carsAdded > 0) add(plural(r.carsAdded, "car") + " added")
            if (r.sessionsAdded > 0) add(plural(r.sessionsAdded, "session") + " added")
            if (r.filesRestored > 0 || r.prefsChanged) add("settings restored")
        }
        val kept = if (r.sessionsKept > 0) " " + plural(r.sessionsKept, "session") + " already here kept." else ""
        return if (parts.isEmpty()) {
            "Restored. Everything in the backup was already here.$kept"
        } else {
            "Restored: " + parts.joinToString(", ") + "." + kept
        }
    }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
