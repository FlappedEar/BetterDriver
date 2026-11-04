@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.format.fixed
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Between two sessions at a new track (Moto Park, 2026-11-08): the iPhone shares the session it just
 * recorded, AirDrop hands the file to the iPad, and the iPad, which has never seen the track, shows
 * its laps, where to find time, the line plan and the lap picker for Compare at once. The track goes
 * with the session; no internet is needed.
 */
class AirDropToIpadTest {
    private val base = NSTemporaryDirectory() + "airdrop-test-" + NSUUID().UUIDString
    private val phone = ReviewLibrary("$base/phone")
    private val pad = ReviewLibrary("$base/pad")

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    @Test
    fun aSessionSharedFromTheIphoneReviewsOnAnIpadThatHasNeverSeenTheTrack() {
        val id = phone.addDemo("$DemoAssetsDir/jastrzab.replay", "$DemoAssetsDir/jastrzab.track.json", StartUtcMillis)
        val onPhone = phone.review(id).review
        assertEquals(emptyList(), pad.tracks(), "the iPad starts without the track")

        val file = phone.share(id, "$base/airdrop") { _, _ -> }
        assertTrue(file.endsWith(".bdsession"), file)
        val size = (NSFileManager.defaultManager.attributesOfItemAtPath(file, null)?.get("NSFileSize") as? NSNumber)
            ?.longLongValue ?: -1L
        // A 25 min session with every source is about 11 MB (TrackDaySoakTest): seconds over AirDrop.
        assertTrue(size in 1..MaxBundleBytes, "bundle of $size bytes")

        val received = pad.receive(file) { _, _ -> }
        assertEquals(id, received.session.sessionId)
        assertEquals(ImportOutcome.ADDED, pad.accept(received, replace = false, replaceTrack = false))
        assertEquals(listOf(id), pad.sessions().map { it.id })
        assertEquals(listOf("jastrzab"), pad.tracks().map { it.id }, "the track came with the session")

        val reviewed = pad.review(id)
        val review = reviewed.review
        assertEquals(onPhone.laps.size, review.laps.size, "laps on the iPad")
        assertTrue(review.laps.size >= 2, "${review.laps.size} laps")
        assertEquals(fixed(checkNotNull(onPhone.bestLapSeconds), 3), fixed(checkNotNull(review.bestLapSeconds), 3))
        assertEquals(
            fixed(checkNotNull(onPhone.theoreticalBestSeconds), 3),
            fixed(checkNotNull(review.theoreticalBestSeconds), 3),
        )
        assertNotNull(review.line, "where to find time on the iPad")
        assertNotNull(reviewed.linePlan, "the line plan on the iPad")
        assertNotNull(reviewed.focusPlan, "the next session's focus on the iPad")

        val picker = pad.lapPicker().single { it.id == "jastrzab" }
        val laps = picker.sessions.single { it.id == id }.laps
        assertTrue(laps.size >= 2, "${laps.size} laps to compare on the iPad")
    }

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val MaxBundleBytes = 50_000_000L
    }
}
