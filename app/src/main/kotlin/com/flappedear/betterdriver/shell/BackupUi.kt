package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.transfer.BackupSummary
import com.flappedear.betterdriver.transfer.BackupTexts
import com.flappedear.betterdriver.transfer.RestoreResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The words are shared with the iOS app (BackupTexts, FBBD-372); only dates are formatted here.

/** File name offered for a new backup (FBBD-156), with the local date: BetterDriver-backup-2026-10-12.bdbackup. */
fun backupFileName(nowUtcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    BackupTexts.fileName(DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(nowUtcMillis).atZone(zone)))

/** Under "Include sessions" in the backup dialog: how many and how big, or null while counting. */
fun sessionsOptionText(sessions: Pair<Int, Long>?): String =
    BackupTexts.sessionsOption(sessions?.first, sessions?.second ?: 0L)

/** When the backup was made and by which version, at the top of the restore sheet. */
fun backupMadeText(s: BackupSummary, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)
        .format(Instant.ofEpochMilli(s.createdUtcMillis).atZone(zone))
    return BackupTexts.made(at, s.appVersion)
}

/** What the backup holds, as label and value rows for the restore sheet. */
fun backupContentRows(s: BackupSummary): List<Pair<String, String>> = BackupTexts.contentRows(s)

/** The line under Back up and Restore after a restore. */
fun restoreResultText(r: RestoreResult): String = BackupTexts.result(r)
