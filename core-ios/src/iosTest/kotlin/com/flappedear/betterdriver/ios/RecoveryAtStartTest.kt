@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app's start recovers a session cut off by a kill, as Android's does (FBBD-71, audit BD-05). */
class RecoveryAtStartTest {
    private val base = NSTemporaryDirectory() + "recovery-test-" + NSUUID().UUIDString
    private val store = SessionStore("$base/sessions")
    private val gnss = FakeGnss()
    private val info = SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(base, null)
    }

    @Test
    fun aSessionCutOffIsRecoveredOnceWithItsDurationAndSamples() {
        val rec = Recorder(store).start(info)
        repeat(50) { rec.submit(SampleSet(it * 40_000L, gnss.fix(it.toLong()))) }
        rec.stop()
        // As if the app had been killed: no clean end record.
        NSFileManager.defaultManager.removeItemAtPath("${rec.dir}/${SessionFileNames.FINISHED}", null)
        assertEquals(SessionState.INTERRUPTED, store.list().single().state)

        val lines = ReviewLibrary(base).recoverInterrupted()
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("${rec.id} recovered"), lines.single())
        val listed = store.list().single()
        assertEquals(SessionState.RECOVERED, listed.state)
        assertEquals(49 * 40_000L, listed.durationMicros)
        assertEquals(50, store.read(rec.id).sampleSets.size)
        assertEquals(emptyList(), ReviewLibrary(base).recoverInterrupted(), "done once")
    }

    @Test
    fun aSessionStillRecordingIsLeftAlone() {
        val rec = Recorder(store).start(info)
        rec.submit(SampleSet(0, gnss.fix(0)))
        assertEquals(emptyList(), ReviewLibrary(base).recoverInterrupted())
        assertEquals(SessionState.RECORDING, store.list().single().state)
        rec.stop()
        assertEquals(SessionState.FINISHED, store.list().single().state)
    }
}
