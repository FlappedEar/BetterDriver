package com.flappedear.betterdriver.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FBBD-209: the recorder binding outlives the activity a new one replaces. */
class BindHoldersTest {
    private val old = Any()
    private val new = Any()

    @Test
    fun `an activity started before the old one stops keeps the binding`() {
        val holders = BindHolders()
        holders.add(old)
        // Relaunched (a new task, a notification tap): the new activity starts, then the old one stops.
        holders.add(new)
        assertFalse("the old activity's stop let the binding go", holders.remove(old))
        assertTrue(holders.remove(new))
    }

    @Test
    fun `the binding goes with the last activity`() {
        val holders = BindHolders()
        holders.add(old)
        assertTrue(holders.remove(old))
        holders.add(new)
        holders.add(new)
        assertTrue("one stop per activity, however often it started", holders.remove(new))
        assertTrue("a stop with nothing held", holders.remove(old))
    }
}
