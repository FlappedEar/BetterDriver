@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.format.fixed
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
    }
}
