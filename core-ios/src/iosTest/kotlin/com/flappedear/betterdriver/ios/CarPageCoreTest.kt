package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.setup.CanIdRecord
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.CarScanRecord
import com.flappedear.betterdriver.setup.Setup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the iPhone's car page shows and saves (FBBD-344), as the Android app's CarParameters. */
class CarPageCoreTest {
    private val frame =
        CanIdRecord(id = 0x0D4, perSecond = 50.0, length = 8, changing = listOf(4, 5), last = "000000001E000000")
    private val scan = CarScanRecord(
        atMs = 0,
        protocol = "A6",
        supported = listOf(0x00, 0x05, 0x0C, 0x20, 0x99),
        canHeard = true,
        canIds = listOf(frame),
    )
    private val setup = Setup(cars = listOf(CarProfile("frs", "Scion FR-S", scan = scan), CarProfile("c", "Corolla")))

    @Test
    fun theScanReadsAsOnAndroid() {
        assertEquals(listOf(0x05, 0x0C, 0x99), CarPageCore.reportedPids(scan))
        assertEquals("bytes 4–5 change", CarPageCore.changingText(listOf(5, 4)))
        assertEquals("byte 2 changes", CarPageCore.changingText(listOf(2)))
        assertEquals("no byte changed", CarPageCore.changingText(emptyList()))
        assertEquals("50/s", CarPageCore.rate(50.0))
        assertEquals("2.5/s", CarPageCore.rate(2.5))
        assertEquals("the adapter would not listen", CarPageCore.noCanReason(scan))
        assertEquals("Coolant temp", CarPageCore.standardName(0x05))
        assertNull(CarPageCore.standardName(0x99))
        assertEquals("0D0", CarPageCore.canIdText(0x0D0))
        // The FR-S by its name reads its steering frame.
        assertTrue(CarPageCore.canIdNames(setup, "frs", 0x0D0).isNotEmpty())
        assertTrue(CarPageCore.canIdNames(setup, "c", 0x0D0).isEmpty())
    }

    @Test
    fun aParameterIsSavedChangedAndRemoved() {
        val draft = ParameterDraft.can(0x0D4).copy(equation = "E * 2", unit = "bar", name = "Brake")
        assertTrue(draft.sourceValid && draft.equationValid)
        assertEquals("60.0 bar", draft.valueOn(CarPageCore.scanFrame(setup, "frs", draft.canIdValue)))
        val saved = CarPageCore.saveParameter(setup, "frs", null, draft)
        val p = saved.car("frs")!!.parameters.single()
        assertEquals("brake", p.key)
        assertEquals("CAN 0D4 · E * 2", CarPageCore.parameterSource(p))
        val renamed = CarPageCore.saveParameter(saved, "frs", "brake", ParameterDraft.of(p).copy(name = "Brake front"))
        assertEquals("Brake front", renamed.car("frs")!!.parameters.single().name)
        assertTrue(CarPageCore.removeParameter(renamed, "frs", "brake").car("frs")!!.parameters.isEmpty())
    }

    @Test
    fun anUnfinishedDraftOrAMissingCarChangesNothing() {
        assertSame(setup, CarPageCore.saveParameter(setup, "frs", null, ParameterDraft.can(0x0D4)))
        val bad = ParameterDraft.empty().copy(canId = "FFFF", equation = "A", name = "x")
        assertSame(setup, CarPageCore.saveParameter(setup, "frs", null, bad))
        val noCar = ParameterDraft.can(1).copy(equation = "A", name = "x")
        assertSame(setup, CarPageCore.saveParameter(setup, "gone", null, noCar))
        assertSame(setup, CarPageCore.recordPid(setup, "gone", 0x05, true))
        val obd = ParameterDraft.pid(0x0C).copy(header = "7E0", equation = "(A*256+B)/4", name = "RPM 2")
        assertEquals(CarParameterSource.OBD, obd.source)
        assertTrue(obd.sourceValid)
        assertEquals("OBD 7E0 01 0C · (A*256+B)/4", CarPageCore.parameterSource(obd.toParameter("k")!!))
    }

    @Test
    fun aSwitchStartsFromTheRecordersPick() {
        val before = CarPageCore.recordedPids(setup, "frs")
        assertTrue(0x0C in before)
        val off = CarPageCore.recordPid(setup, "frs", 0x0C, false)
        assertEquals(before - 0x0C, CarPageCore.recordedPids(off, "frs"))
    }
}
