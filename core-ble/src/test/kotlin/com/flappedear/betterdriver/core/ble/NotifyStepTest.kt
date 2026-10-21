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
