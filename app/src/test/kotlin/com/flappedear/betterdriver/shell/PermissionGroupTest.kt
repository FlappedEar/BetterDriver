package com.flappedear.betterdriver.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGroupTest {
    @Test
    fun `each denied permission says what will not work`() {
        val lines = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
        ).map { deniedGroups(listOf(it)).single().deniedLine() }
        assertEquals(
            listOf(
                "Location off: no GNSS from the phone; Bluetooth GPS receivers also need it",
                "Nearby devices off: no RaceBox, GoPro or Bluetooth receivers",
                "Camera off: no phone video",
                "Microphone off: video without sound",
                "Notifications off: no recording notification",
            ),
            lines,
        )
    }

    @Test
    fun `a group shows once, in a fixed order, whichever of its permissions is denied`() {
        val denied = listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        )
        assertEquals(
            listOf(PermissionGroup.Location, PermissionGroup.NearbyDevices, PermissionGroup.Microphone),
            deniedGroups(denied),
        )
    }

    @Test
    fun `nothing denied, or only permissions outside the groups, gives no lines`() {
        assertTrue(deniedGroups(emptyList()).isEmpty())
        assertTrue(deniedGroups(listOf("android.permission.INTERNET")).isEmpty())
    }

    @Test
    fun `every permission the app asks for belongs to a group`() {
        val asked = setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
        )
        assertEquals(asked, PermissionGroup.entries.flatMap { it.permissions }.toSet())
    }
}
