package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.review.ReviewOnlyDevice
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewOnlyDeviceTest {
    private val topLevel = listOf("Drive", "Sessions", "Car", "Settings")
    private val settings = listOf(
        "Equipment", "Cars", "Tracks", "Track screen", "Video", "Spoken coach", "App", "Diagnostics",
    )

    @Test
    fun `a phone shows everything`() {
        assertEquals(topLevel, topLevel.filter { ReviewOnlyDevice.showsTopLevel(it, tablet = false) })
        assertEquals(settings, settings.filter { ReviewOnlyDevice.showsSetting(it, tablet = false) })
    }

    @Test
    fun `a tablet only reviews`() {
        assertEquals(
            listOf("Sessions", "Settings"),
            topLevel.filter { ReviewOnlyDevice.showsTopLevel(it, tablet = true) },
        )
        assertEquals(
            listOf("Cars", "Tracks", "App", "Diagnostics"),
            settings.filter { ReviewOnlyDevice.showsSetting(it, tablet = true) },
        )
    }
}
