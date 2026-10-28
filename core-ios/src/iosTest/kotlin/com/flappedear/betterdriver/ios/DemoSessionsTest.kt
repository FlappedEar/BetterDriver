@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.store.DeleteResult
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.stringWithContentsOfFile
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

    private fun read(path: String) = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
    }
}
