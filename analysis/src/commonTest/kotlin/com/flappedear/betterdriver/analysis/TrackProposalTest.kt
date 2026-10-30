package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.trackProposal
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A track made from a session at no saved track (FBBD-151, FBBD-367), as Android's analysis offers it. */
class TrackProposalTest {
    private val track = TrackJson.decode(demoAsset("jastrzab.track.json"))

    private val session: SessionData = run {
        val replay = SessionReplay.read(demoAsset("jastrzab.replay"), StartUtcMillis, RateHz)
        val seconds = demoAsset("jastrzab.replay").lineSequence().last { it.isNotBlank() }
            .substringBefore(' ').toDouble()
        val sets = (0 until (seconds * RateHz).toLong())
            .map { SampleSet(it * MicrosPerSecond / RateHz, replay.fix(it)) }
        val info = SessionInfo(StartUtcMillis, channels = GnssChannels.ALL)
        SessionData(
            "s", info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets, emptyList(), null, 0, 0,
        )
    }

    @Test
    fun `laps of a loop at no saved track propose a new track with its line on the longest straight`() {
        val p = assertNotNull(trackProposal(session, emptyList(), CircuitList.Empty, "Morning"))
        assertEquals("Track of Morning", p.track.name)
        assertNotNull(p.track.startFinish)
        assertEquals("Morning", p.label)
        assertTrue(p.fixes.size > 1000)
        assertTrue(p.text.startsWith("No laps yet: this session drove "), p.text)
        assertTrue(p.text.contains(" laps of a ") && p.text.contains(" km loop that has no start/finish line."), p.text)
    }

    @Test
    fun `a saved track here without a line gets it and one with a line offers nothing`() {
        assertNull(trackProposal(session, listOf(track), CircuitList.Empty, "Morning"))
        val lineless = track.copy(startFinish = null)
        val p = assertNotNull(trackProposal(session, listOf(lineless), CircuitList.Empty, "Morning"))
        assertEquals(track.id, p.track.id)
        assertNotNull(p.track.startFinish)
        assertTrue(p.text.contains(" at ${track.displayName} "), p.text)
    }

    private companion object {
        const val StartUtcMillis = 1_790_000_000_000L
        const val RateHz = 10
        const val MicrosPerSecond = 1_000_000L
    }
}
