@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.feature.analysis.ProgressionPeriod
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The progression at a track (FBBD-181) and the summary's card against earlier sessions (FBBD-122)
 * on the iPhone and iPad, from the built-in demos: the sessions at the track only, worded by the
 * shared core as on Android.
 */
class TrackProgressionsTest {
    private val base = NSTemporaryDirectory() + "progression-test-" + NSUUID().UUIDString
    private val library = ReviewLibrary(base)
    private val jastrzab = "$DemoAssetsDir/jastrzab.replay" to "$DemoAssetsDir/jastrzab.track.json"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    private fun addJastrzab(start: Long) = library.addDemo(jastrzab.first, jastrzab.second, start)

    @Test
    fun theSessionsAtATrackMakeItsProgression() {
        val ids = listOf(0L, 1L, 30L).map { addJastrzab(StartUtcMillis + it * DayMillis) }
        val silesia = "$DemoAssetsDir/silesia-ring.replay" to "$DemoAssetsDir/silesia-ring.track.json"
        library.addDemo(silesia.first, silesia.second, StartUtcMillis + 5 * DayMillis)
        val track = assertNotNull(library.progressions.trackOf(ids.last()))
        assertEquals("jastrzab", track.id)
        val lefts = mutableListOf<Int>()
        var added = 0
        library.progressions.addNewest(track.id, { lefts += it }) { added++ }
        assertEquals(3, added)
        assertEquals(0, lefts.last())
        fun screen(grouping: ProgressionGrouping) = library.progressions.screen(
            track.id, track.name, grouping, ProgressionPeriod.All, null, emptySet(), 0,
        )
        val sessions = screen(ProgressionGrouping.Session)
        assertEquals(ids.reversed(), sessions.points.map { it.key })
        assertEquals("3 sessions on 3 days", sessions.summary)
        assertEquals("Best lap", sessions.laps.first().label)
        assertEquals("1:49.91", sessions.laps.first().latest)
        // The same laps three times: steady.
        assertEquals("Steady over 3 sessions", sessions.laps.first().trend)
        assertTrue(sessions.corners.isNotEmpty())
        // 21 and 22 September, 21 October.
        assertEquals(2, screen(ProgressionGrouping.Month).points.size)
        // Opened again, nothing is read twice.
        var again = 0
        library.progressions.addNewest(track.id, {}) { again++ }
        assertEquals(0, again)
        // A session left out stays listed.
        val left = library.progressions.screen(
            track.id, track.name, ProgressionGrouping.Session, ProgressionPeriod.All, null, setOf(ids[0]), 0,
        )
        assertEquals(3, left.points.size)
        assertTrue(left.points.single { it.key == ids[0] }.excluded)
    }

    @Test
    fun aSessionIsComparedWithTheEarlierSessionsAtItsTrack() {
        val first = addJastrzab(StartUtcMillis)
        val second = addJastrzab(StartUtcMillis + DayMillis)
        val alone = assertNotNull(library.progress(library.review(first), first))
        assertEquals(0, alone.earlierSessions)
        val progress = assertNotNull(library.progress(library.review(second), second))
        assertEquals(1, progress.earlierSessions)
        assertEquals(0.0, progress.bestDeltaSeconds)
        assertNotNull(progress.previousLabel)
        assertTrue(progress.eligibleLapCount > 0)
    }

    @Test
    fun aSessionAtNoTrackHasNoProgression() {
        val id = addJastrzab(StartUtcMillis)
        NSFileManager.defaultManager.removeItemAtPath("$base/tracks", null)
        assertNull(library.progressions.trackOf(id))
        assertNull(library.progress(library.review(id), id))
    }

    @Test
    fun theSummaryKeepsTheSessionInTheCoachingHistoryAsAndroid() {
        val first = addJastrzab(StartUtcMillis)
        val second = addJastrzab(StartUtcMillis + DayMillis)
        // The first has nothing before it; the second shows what came of the first's focus, as
        // Android's card (FBBD-364): its three corners, measured through both sessions' line plans.
        assertTrue(library.coaching(library.review(first), first).empty)
        val card = library.coaching(library.review(second), second)
        assertEquals(3, card.attempts.size)
        assertTrue(card.checks.isEmpty())
        assertNotNull(card.wonBack)
        // Each session's record is kept where Android keeps it, with the corners the track now keeps.
        val history = "$base/coach/history/jastrzab"
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$history/$first.json"))
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$history/$second.json"))
        assertNotNull(library.tracks().single { it.id == "jastrzab" }.identity)
        // The progression reads the kept records, so a library opened again reads no session.
        val again = ReviewLibrary(base)
        val track = assertNotNull(again.progressions.trackOf(second))
        var read = 0
        again.progressions.addNewest(track.id, {}) { read++ }
        assertEquals(0, read)
        val screen = again.progressions.screen(
            track.id, track.name, ProgressionGrouping.Session, ProgressionPeriod.All, null, emptySet(), 0,
        )
        assertEquals(listOf(second, first), screen.points.map { it.key })
        // The history goes with its track, as on Android.
        assertTrue(TrackLibrary("$base/tracks", "$base/sessions").delete(track.id))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(history))
    }

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val DayMillis = 24 * 3_600_000L
    }
}
