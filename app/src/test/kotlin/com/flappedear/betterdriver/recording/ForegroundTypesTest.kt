package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.recording.RecorderForegroundType.ConnectedDevice
import com.flappedear.betterdriver.recording.RecorderForegroundType.Location
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTypesTest {
    private val none = emptySet<RecorderForegroundType>()

    @Test
    fun `Android 8 and 9 have no types`() {
        assertEquals(none, recorderForegroundTypes(26, locationGranted = false, bluetoothConnectGranted = false))
        assertEquals(none, recorderForegroundTypes(28, locationGranted = true, bluetoothConnectGranted = true))
    }

    @Test
    fun `Android 10 and 11 claim both, as neither needs a runtime permission there`() {
        assertEquals(setOf(Location, ConnectedDevice), recorderForegroundTypes(29, false, false))
        assertEquals(setOf(Location, ConnectedDevice), recorderForegroundTypes(30, true, false))
    }

    @Test
    fun `from Android 12 connected device needs nearby devices`() {
        assertEquals(setOf(Location), recorderForegroundTypes(31, true, false))
        assertEquals(setOf(Location), recorderForegroundTypes(33, false, false))
        assertEquals(setOf(Location, ConnectedDevice), recorderForegroundTypes(33, false, true))
    }

    @Test
    fun `from Android 14 each type needs its permission`() {
        assertEquals(setOf(Location, ConnectedDevice), recorderForegroundTypes(34, true, true))
        assertEquals(setOf(Location), recorderForegroundTypes(34, true, false))
        assertEquals(setOf(ConnectedDevice), recorderForegroundTypes(35, false, true))
        assertEquals(none, recorderForegroundTypes(36, false, false))
    }
}
