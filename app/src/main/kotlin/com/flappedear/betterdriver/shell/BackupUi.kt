package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.transfer.BackupExtension
import com.flappedear.betterdriver.transfer.BackupSummary
import com.flappedear.betterdriver.transfer.RestoreResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BytesPerMb = 1_000_000.0

/** File name offered for a new backup (FBBD-156), with the local date: BetterDriver-backup-2026-10-12.bdbackup. */
fun backupFileName(nowUtcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "BetterDriver-backup-" + DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(nowUtcMillis).atZone(zone)) +
        "." + BackupExtension

/** Under "Include sessions" in the backup dialog: how many and how big, or null while counting. */
fun sessionsOptionText(sessions: Pair<Int, Long>?): String = when {
    sessions == null -> "Counting…"
    sessions.first == 0 -> "No sessions yet"
    else -> plural(sessions.first, "session") + String.format(Locale.ROOT, ", %.1f MB", sessions.second / BytesPerMb)
}

/** When the backup was made and by which version, at the top of the restore sheet. */
fun backupMadeText(s: BackupSummary, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)
        .format(Instant.ofEpochMilli(s.createdUtcMillis).atZone(zone))
    return if (s.appVersion.isBlank()) "Made $at" else "Made $at with BetterDriver ${s.appVersion}"
}

/** What the backup holds, as label and value rows for the restore sheet. */
fun backupContentRows(s: BackupSummary): List<Pair<String, String>> = listOf(
    "Tracks" to s.tracks.toString(),
    "Equipment" to s.equipment.toString(),
    "Cars" to s.cars.toString(),
    "Sessions" to if (s.withSessions) "${s.sessions}, without video" else "Not included",
    "Coach settings and references" to if (s.coachFiles == 0) "None" else plural(s.coachFiles, "file"),
    "Sensor calibrations" to s.calibrations.toString(),
    "App settings" to if (s.prefs) "Included" else "None",
)

/** The line under Back up and Restore after a restore. */
fun restoreResultText(r: RestoreResult): String {
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
