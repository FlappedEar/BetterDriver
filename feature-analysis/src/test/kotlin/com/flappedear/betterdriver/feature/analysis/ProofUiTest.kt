package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.line.CornerProof
import com.flappedear.betterdriver.analysis.line.ProofVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** What came of the last focus in words (FBBD-310, UI-191 A). */
class ProofUiTest {
    private fun proof(before: Double, after: Double?, verdict: ProofVerdict, cues: Int?, target: Double = 9.70) =
        CornerProof("c1", "T11", before, after, target, verdict, cues)

    @Test
    fun `each corner says the time back, what is left to the target and whether the coach spoke there`() {
        assertEquals(
            "0.20 s back · 0.05 s still to your target · the coach spoke here 4 times",
            proofText(proof(9.95, 9.75, ProofVerdict.Faster, 4)),
        )
        assertEquals(
            "0.20 s back · on your target now · the coach spoke here once",
            proofText(proof(9.95, 9.75, ProofVerdict.Faster, 1, target = 9.80)),
        )
        assertEquals("0.07 s back without a cue from the coach", proofText(proof(7.20, 7.13, ProofVerdict.Faster, 0)))
        assertEquals(
            "0.20 s back · 0.05 s still to your target",
            proofText(proof(9.95, 9.75, ProofVerdict.Faster, null)),
        )
        assertEquals(
            "0.02 s slower: within GPS timing · no cue here",
            proofText(proof(8.41, 8.43, ProofVerdict.Same, 0)),
        )
        assertEquals("The same time: within GPS timing", proofText(proof(8.41, 8.41, ProofVerdict.Same, null)))
        assertEquals(
            "0.15 s slower · the coach spoke here 2 times",
            proofText(proof(8.40, 8.55, ProofVerdict.Slower, 2)),
        )
        assertEquals("No pass through it this session", proofText(proof(8.40, null, ProofVerdict.NotDriven, 0)))
    }
}
