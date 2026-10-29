@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.setup.CarScanRecord
import com.flappedear.betterdriver.setup.Setup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-261, FBBD-344: the car data page's scan run, texts and stored scan on iOS, as on Android. */
class ObdPageTest {
    private val scan = CarScan("A6", setOf(0x0C, 0x0D), "JTDKB", emptyList(), canHeard = false)

    @Test
    fun aScanComesBackOnceAndEndsTheRun() {
        val run = ObdScanRun()
        run.start(1_000)
        assertTrue(run.asked)
        assertNull(run.check(ObdStatus(ObdLinkState.Connecting, "vLinker"), 2_000))
        assertEquals(scan, run.check(ObdStatus(ObdLinkState.Connected, "vLinker", scan = scan), 3_000))
        assertTrue(!run.asked)
        assertNull(run.since)
        // The same scan, kept by the tracker across reconnects, is not stored again.
        assertNull(run.check(ObdStatus(ObdLinkState.Connected, "vLinker", scan = scan), 4_000))
    }

    @Test
    fun aScanThatDoesNotFinishGivesUpAndSaysWhy() {
        val run = ObdScanRun()
        run.start(0)
        assertNull(run.check(ObdStatus(ObdLinkState.Searching, "no adapter answered"), 61_000))
        assertTrue(!run.asked)
        assertEquals(
            "The scan did not finish: no adapter answered. Switch the ignition on and try again.",
            run.note,
        )
    }

    @Test
    fun theScanIsStoredWithItsCar() {
        val fresh = Setup().addCar("Corolla") { "car-1" }
        val carId = fresh.cars.first().id
        val stored = withCarScan(fresh, carId, scan.toRecord(42L))
        val record: CarScanRecord? = stored.car(carId)?.scan
        assertNotNull(record)
        assertEquals(listOf(0x0C, 0x0D), record.supported)
        assertEquals(fresh, withCarScan(fresh, "no such car", scan.toRecord(42L)))
    }

    @Test
    fun thePageSaysWhatAndroidSays() {
        val on = ObdStatus(ObdLinkState.Connected, "line", listOf(ObdReading("rpm", "RPM", "rpm", 2850.0, 9.14)))
        assertEquals("Connected · 1 value", ObdPageText.title(false, on))
        assertEquals("Recording · 1 value", ObdPageText.title(true, on))
        val off = ObdStatus(ObdLinkState.Searching, "none")
        assertEquals("No adapter", ObdPageText.title(false, off))
        assertEquals("Wi-Fi adapter not answering · trying again", ObdPageText.detail(off, "Wi-Fi adapter"))
        assertTrue(ObdPageText.note(false, off, "Wi-Fi adapter", wifi = true).startsWith("Join the adapter's Wi-Fi"))
        assertEquals("9.1 Hz", ObdPageText.rate(on.readings.single()))
    }
}
