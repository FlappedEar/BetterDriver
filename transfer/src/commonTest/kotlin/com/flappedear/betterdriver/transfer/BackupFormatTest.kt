package com.flappedear.betterdriver.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The backup's words, preferences and file kinds, the same on Android and iOS (FBBD-372). */
class BackupFormatTest {
    private val summary = BackupSummary(
        createdUtcMillis = 1, appVersion = "1.25.0", tracks = 3, equipment = 2, cars = 1, sessions = 4,
        calibrations = 0, coachFiles = 1, prefs = false, withSessions = true,
    )

    @Test
    fun theWordsAreAndroidsWords() {
        assertEquals("BetterDriver-backup-2026-10-31.bdbackup", BackupTexts.fileName("2026-10-31"))
        assertEquals("Counting…", BackupTexts.sessionsOption(null, 0))
        assertEquals("No sessions yet", BackupTexts.sessionsOption(0, 0))
        assertEquals("2 sessions, 1.5 MB", BackupTexts.sessionsOption(2, 1_500_000))
        assertEquals(
            "Made 31 Oct 2026, 22:00 with BetterDriver 1.25.0",
            BackupTexts.made("31 Oct 2026, 22:00", "1.25.0"),
        )
        assertEquals("Made 31 Oct 2026, 22:00", BackupTexts.made("31 Oct 2026, 22:00", " "))
        assertEquals("4, without video", BackupTexts.contentRows(summary)[3].second)
        assertEquals("1 file", BackupTexts.contentValues(summary)[4])
        assertEquals("None", BackupTexts.contentValues(summary)[6])
        assertEquals(
            "Restored: 2 tracks added, 1 car added. 1 session already here kept.",
            BackupTexts.result(RestoreResult(tracksAdded = 2, carsAdded = 1, sessionsKept = 1)),
        )
        assertEquals("Restored. Everything in the backup was already here.", BackupTexts.result(RestoreResult()))
    }

    @Test
    fun preferencesKeepTheirTypes() {
        val prefs = mapOf(
            "b" to true, "i" to 1, "l" to 2L, "f" to 0.5f, "s" to "a:b=c", "set" to setOf("x"), "n" to null,
        )
        assertEquals(
            mapOf<String, Any>("b" to true, "i" to 1, "l" to 2L, "f" to 0.5f, "s" to "a:b=c"),
            BackupPrefs.kept(prefs),
        )
        assertFailsWith<BundleDamagedException> { BackupPrefs.decode("x=i\\:nope\n".encodeToByteArray()) }
    }

    @Test
    fun aFileIsToldByItsFirstBytes() {
        assertEquals(IncomingKind.SESSION, TrackShare.kindOf("BDBUNDLE".encodeToByteArray()))
        assertEquals(IncomingKind.BACKUP, TrackShare.kindOf("BDBACKUP".encodeToByteArray()))
        assertEquals(IncomingKind.TRACK, TrackShare.kindOf("  {\"id\"".encodeToByteArray()))
        assertEquals(IncomingKind.UNKNOWN, TrackShare.kindOf("File cre".encodeToByteArray()))
    }

    @Test
    fun theManifestIsCheckedAndANewerOneRefused() {
        val m = BackupFormat.readManifest(BackupFormat.manifest(5, "1.25.0", true))
        assertEquals("5", m["created"])
        assertEquals("true", m["sessions"])
        assertFailsWith<BundleDamagedException> { BackupFormat.readManifest("format=other\n".encodeToByteArray()) }
        assertFailsWith<BundleDamagedException> {
            BackupFormat.readManifest("format=betterdriver-backup\nversion=99\n".encodeToByteArray())
        }
        assertEquals(BackupFormat.MaxTrackBytes, BackupFormat.maxBytes("tracks/a.track.json"))
        assertEquals(null, BackupFormat.maxBytes("sessions/x/video/a.mp4"))
    }
}
