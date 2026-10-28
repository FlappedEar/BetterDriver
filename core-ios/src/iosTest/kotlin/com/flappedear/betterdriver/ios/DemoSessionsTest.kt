@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.feature.analysis.TrackDayState
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.store.DeleteResult
import com.flappedear.betterdriver.timing.track.TrackJson
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A built-in demo added to the iOS store reviews with the laps DemoParityTest checks on every
 * platform (FBBD-331): the session the app's UI tests open is the one Android would show.
 */
class DemoSessionsTest {
    private val base = NSTemporaryDirectory() + "demo-sessions-test-" + NSUUID().UUIDString
    private val library = ReviewLibrary(base)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    @Test
    fun theJastrzabDemoReviewsWithTheLapsOfEveryPlatform() {
        val id = library.addDemo("$DemoAssetsDir/jastrzab.replay", "$DemoAssetsDir/jastrzab.track.json", StartUtcMillis)
        assertEquals(listOf(id), library.sessions().map { it.id })
        val review = library.review(id).review
        assertEquals("jastrzab", review.track?.id)
        assertEquals(23, review.laps.size)
        assertEquals(18, review.laps.single { it.fastest }.number)
        assertEquals("109.906", fixed(checkNotNull(review.bestLapSeconds), 3))
        assertEquals("108.201", fixed(checkNotNull(review.theoreticalBestSeconds), 3))
        assertNotNull(review.line)
    }

    @Test
    fun aDeletedSessionLeavesTheList() {
        val id = library.addDemo("$DemoAssetsDir/jastrzab.replay", "$DemoAssetsDir/jastrzab.track.json", StartUtcMillis)
        assertEquals(DeleteResult.DELETED, library.delete(id))
        assertEquals(emptyList(), library.sessions())
        assertEquals(DeleteResult.NOT_FOUND, library.delete(id))
    }

    @Test
    fun csvAndGpxHaveTheLapsAndTheTimesAsOnAndroid() {
        val id = library.addDemo("$DemoAssetsDir/jastrzab.replay", "$DemoAssetsDir/jastrzab.track.json", StartUtcMillis)
        val export = SessionExport("$base/sessions", "$base/tracks", "$base/out")
        val csv = checkNotNull(read(export.csv(id))).split("\r\n")
        assertTrue(csv[0].startsWith("time,elapsed (s),lap,lap time (s),"), csv[0])
        val time = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}[+-]\d\d:\d\d""")
        assertTrue(time.matches(csv[1].substringBefore(',')), csv[1])
        val lapNumbers = csv.drop(1).mapNotNull { it.split(',').getOrNull(2)?.toIntOrNull() }.toSet()
        assertTrue(23 in lapNumbers, "laps $lapNumbers")
        val gpx = checkNotNull(read(export.gpx(id)))
        assertTrue("<time>2026-09-21T14:13:20.000Z</time>" in gpx, gpx.take(800))
        assertTrue("<!-- Lap 23 -->" in gpx)
    }

    @Test
    fun aSharedSessionImportsElsewhereWithItsTrackAndLaps() {
        val id = library.addDemo("$DemoAssetsDir/jastrzab.replay", "$DemoAssetsDir/jastrzab.track.json", StartUtcMillis)
        var last = 0L to 0L
        val bundle = library.share(id, "$base/out") { written, total -> last = written to total }
        assertTrue(bundle.endsWith("/BetterDriver-$id.bdsession"), bundle)
        assertTrue(last.second > 0 && last.first == last.second, "progress $last")
        val other = ReviewLibrary("$base/other")
        val received = other.receive(bundle) { _, _ -> }
        assertEquals("jastrzab", received.session.track?.id)
        other.accept(received, replace = false, replaceTrack = false)
        assertEquals(listOf(id), other.sessions().map { it.id })
        val review = other.review(id).review
        assertEquals(23, review.laps.size)
        assertEquals("109.906", fixed(checkNotNull(review.bestLapSeconds), 3))
    }

    @Test
    fun aBundlePreparedOverSixHoursAgoIsRemovedWhenTheNextIsShared() {
        val id = library.addDemo("$DemoAssetsDir/jastrzab.replay", "$DemoAssetsDir/jastrzab.track.json", StartUtcMillis)
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath("$base/out", true, null, null)
        val old = "$base/out/BetterDriver-old.bdsession"
        val recent = "$base/out/BetterDriver-recent.bdsession"
        fm.createFileAtPath(old, null, null)
        fm.createFileAtPath(recent, null, null)
        val sevenHoursAgo = NSDate.dateWithTimeIntervalSinceNow(-7 * 60 * 60.0)
        fm.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to sevenHoursAgo), old, null)
        library.share(id, "$base/out") { _, _ -> }
        assertFalse(fm.fileExistsAtPath(old), "a bundle prepared 7 h ago stayed")
        assertTrue(fm.fileExistsAtPath(recent), "a recent bundle was removed")
    }

    @Test
    fun twoSessionsAtOneTrackMakeATrackDayAndTheNextDayMeasuresIt() {
        val demo = "$DemoAssetsDir/jastrzab.replay" to "$DemoAssetsDir/jastrzab.track.json"
        val day1 = listOf(StartUtcMillis, StartUtcMillis + HourMillis)
            .map { library.addDemo(demo.first, demo.second, it) }
        val first = library.trackDay(day1)
        assertTrue(first is TrackDayState.Ready, "$first")
        val ui = first.ui
        assertTrue(ui.title.startsWith("Autodrom Jastrząb · "), ui.title)
        assertTrue(ui.subtitle.startsWith("2 sessions · "), ui.subtitle)
        assertEquals("1:49.91", ui.best)
        assertEquals(2, ui.sessions.size)
        assertTrue(ui.next.isNotEmpty())
        assertEquals(null, ui.proof)
        val day2 = listOf(StartUtcMillis + DayMillis, StartUtcMillis + DayMillis + HourMillis)
            .map { library.addDemo(demo.first, demo.second, it) }
        val second = library.trackDay(day2)
        assertTrue(second is TrackDayState.Ready, "$second")
        assertTrue(checkNotNull(second.ui.proof).title.startsWith("Last day here"), "${second.ui.proof}")
        assertTrue(library.trackDay(day1.take(1)) is TrackDayState.Unavailable)
    }

    @Test
    fun aTrackDayKeepsTheCornerIdentityItMadeWithTheTrack() {
        val demo = "$DemoAssetsDir/jastrzab.replay" to "$DemoAssetsDir/jastrzab.track.json"
        val day = listOf(StartUtcMillis, StartUtcMillis + HourMillis)
            .map { library.addDemo(demo.first, demo.second, it) }
        // A track made on the iPhone has no corner identity until an analysis makes one.
        val tracks = TrackLibrary("$base/tracks", "$base/sessions")
        val saved = checkNotNull(tracks.get("jastrzab"))
        NSString.create(string = TrackJson.encode(saved.copy(identity = null)))
            .writeToFile("$base/tracks/jastrzab.json", atomically = true, encoding = NSUTF8StringEncoding, error = null)
        assertTrue(library.trackDay(day) is TrackDayState.Ready)
        val kept = checkNotNull(tracks.get("jastrzab")?.identity) { "no corner identity kept" }
        assertTrue(library.trackDay(day) is TrackDayState.Ready)
        assertEquals(kept, tracks.get("jastrzab")?.identity, "the next day made another identity")
    }

    private fun read(path: String) = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val HourMillis = 3_600_000L
        const val DayMillis = 24 * HourMillis
    }
}
