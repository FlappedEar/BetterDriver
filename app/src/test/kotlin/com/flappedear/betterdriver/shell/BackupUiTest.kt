package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.transfer.BackupSummary
import com.flappedear.betterdriver.transfer.RestoreResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class BackupUiTest {
    private val summary = BackupSummary(
        createdUtcMillis = 1_760_270_700_000, // 2025-10-12 12:05 UTC
        appVersion = "0.3.0 (build 412)",
        tracks = 3,
        equipment = 4,
        cars = 1,
        sessions = 12,
        calibrations = 1,
        coachFiles = 5,
        prefs = true,
        withSessions = true,
    )

    @Test
    fun fileNameHasTheLocalDate() {
        assertEquals("BetterDriver-backup-2025-10-12.bdbackup", backupFileName(1_760_270_700_000, ZoneOffset.UTC))
    }

    @Test
    fun sheetSaysWhenAndWhatTheBackupHolds() {
        assertEquals(
            "Made 12 Oct 2025, 12:05 with BetterDriver 0.3.0 (build 412)",
            backupMadeText(summary, ZoneOffset.UTC),
        )
        val rows = backupContentRows(summary).toMap()
        assertEquals("3", rows["Tracks"])
        assertEquals("12, without video", rows["Sessions"])
        assertEquals("5 files", rows["Coach settings and references"])
        assertEquals("Not included", backupContentRows(summary.copy(withSessions = false)).toMap()["Sessions"])
    }

    @Test
    fun sessionsOptionShowsCountAndSize() {
        assertEquals("Counting…", sessionsOptionText(null))
        assertEquals("No sessions yet", sessionsOptionText(0 to 0L))
        assertEquals("1 session, 2.5 MB", sessionsOptionText(1 to 2_500_000L))
    }

    @Test
    fun resultListsWhatChanged() {
        assertEquals(
            "Restored: 2 tracks added, 1 track updated, 1 car added, settings restored. 3 sessions already here kept.",
            restoreResultText(
                RestoreResult(tracksAdded = 2, tracksUpdated = 1, carsAdded = 1, sessionsKept = 3, prefsChanged = true),
            ),
        )
        assertEquals("Restored. Everything in the backup was already here.", restoreResultText(RestoreResult()))
    }
}
