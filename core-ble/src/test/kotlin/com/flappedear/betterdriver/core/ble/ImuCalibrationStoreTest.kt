package com.flappedear.betterdriver.core.ble

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImuCalibrationStoreTest {
    @Test
    fun `saves, loads and clears per RaceBox`() {
        val store = ImuCalibrationStore(Files.createTempDirectory("cal").toFile())
        val c = ImuCalibration(Vec3(0.1, 0.2, 0.3), listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), 0.0, 0.0, 42)
        assertNull(store.load("AA:BB:CC:DD:EE:FF"))
        store.save("AA:BB:CC:DD:EE:FF", c)
        assertEquals(42L, store.load("aa:bb:cc:dd:ee:ff")?.atMillis)
        assertNull(store.load("11:22:33:44:55:66"))
        store.clear("AA:BB:CC:DD:EE:FF")
        assertNull(store.load("AA:BB:CC:DD:EE:FF"))
    }
}
