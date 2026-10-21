@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reason a [GattNotifyLink] setup gives up with (FBBD-279). On 21 Oct 2026 a heart rate strap
 * ended 67 setups with "no data after connecting" and nothing else, so the log could not tell
 * whether the strap never answered or connected and stayed silent. The reason now names the step.
 */
class NotifyStepTest {
    @Test
    fun reasonNamesTheStepTheSetupWaitedFor() {
        assertEquals(
            "no data after connecting: no answer to the connection in 15012 ms",
            NotifyStep.CONNECT.noDataReason(15_012),
        )
        assertEquals(
            "no data after connecting: notifications on, no value sent in 15003 ms",
            NotifyStep.FIRST_VALUE.noDataReason(15_003),
        )
    }

    @Test
    fun everyReasonKeepsTheOldPrefixAndIsDistinct() {
        val reasons = NotifyStep.entries.map { it.noDataReason(15_000) }
        assertTrue(reasons.all { it.startsWith("no data after connecting") })
        assertEquals(NotifyStep.entries.size, reasons.toSet().size)
    }
}

/**
 * The quiet wait (FBBD-281): an unworn strap on 21 Oct 2026 cost 55 setups in 18 minutes. After
 * [QuietAfterSetups] setups without data the link holds one background connection with no limit.
 */
class NotifyPlanTest {
    @Test
    fun ladderUntilTheQuietWaitThenOneBackgroundConnectionWithoutLimit() {
        val ladder = SetupLadder(15_000L, 30_000L)
        repeat(QuietAfterSetups) { n ->
            assertEquals(ladder.plan(), notifyPlan(n, ladder))
            assertTrue(notifyPlan(n, ladder).setupTimeoutMs <= 30_000L)
            ladder.failed()
        }
        val quiet = notifyPlan(QuietAfterSetups, ladder)
        assertTrue(quiet.autoConnect)
        assertEquals(Long.MAX_VALUE, quiet.setupTimeoutMs)
        assertEquals(quiet, notifyPlan(QuietAfterSetups + 10, ladder))
    }
}
