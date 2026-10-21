package com.flappedear.betterdriver.recorder.log

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReachLogTest {
    private val raceBox = ReachLog.Shown("RACEBOX", "AA:BB:CC:DD:EE:01", "NotSeen")
    private val strap = ReachLog.Shown("HEART_RATE", "AA:BB:CC:DD:EE:02", "InRange")
    private val camera = ReachLog.Shown("PHONE_CAMERA", null, "BuiltIn")

    @Test
    fun `the first state of every item is logged, then only what changed`() {
        val first = mapOf("rb" to raceBox, "hr" to strap, "cam" to camera)
        assertEquals(
            listOf(
                "item=rb model=RACEBOX address=AA:BB:CC:DD:EE:01 reach=NotSeen",
                "item=hr model=HEART_RATE address=AA:BB:CC:DD:EE:02 reach=InRange",
                "item=cam model=PHONE_CAMERA address=- reach=BuiltIn",
            ),
            ReachLog.changes(emptyMap(), first),
        )
        val heard = first + ("rb" to raceBox.copy(reach = "InRange"))
        assertEquals(
            listOf("item=rb model=RACEBOX address=AA:BB:CC:DD:EE:01 reach=InRange"),
            ReachLog.changes(first, heard),
        )
        assertEquals(emptyList(), ReachLog.changes(heard, heard))
        // An item that gets no line on the page is logged as none.
        assertEquals(
            listOf("item=hr model=HEART_RATE address=AA:BB:CC:DD:EE:02 reach=none"),
            ReachLog.changes(heard, heard + ("hr" to strap.copy(reach = null))),
        )
    }

    @Test
    fun `every line reads back`() {
        assertEquals(ReachLog.Entry.Look(true), ReachLog.parse(ReachLog.look(true, 3)))
        assertEquals(ReachLog.Entry.Look(false), ReachLog.parse(ReachLog.look(false, 0)))
        assertEquals(
            ReachLog.Entry.Item("rb", "RACEBOX", "AA:BB:CC:DD:EE:01", "InRange"),
            ReachLog.parse(ReachLog.item("rb", "RACEBOX", "AA:BB:CC:DD:EE:01", "InRange")),
        )
        assertEquals(
            ReachLog.Entry.Item("my_cam", "PHONE_CAMERA", null, "none"),
            ReachLog.parse(ReachLog.item("my cam", "PHONE_CAMERA", null, null)),
        )
        assertNull(ReachLog.parse("item=rb model=RACEBOX"))
        assertNull(ReachLog.parse("something else"))
    }
}
