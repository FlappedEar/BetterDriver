package com.flappedear.betterdriver.smoke

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flappedear.betterdriver.feature.analysis.DriveMapTag
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * A session with no laps (FBBD-342) on the emulator: a drive away from every saved track, as a
 * commute, still shows where it went. Its analysis says there are no laps, then the drive's figures,
 * its route on the map coloured by speed and its speed along the drive.
 */
@RunWith(AndroidJUnit4::class)
class NoLapsSmokeTest {
    private val setup = SmokeSetup()

    @get:Rule
    val rules: TestRule = setup.rules

    private val app get() = setup.compose

    @Before
    fun seed() = SmokeData.seedCommute()

    @After
    fun reset() = SmokeData.reset()

    @Test
    fun aDriveWithNoTrackShowsItsRouteAndSpeed() {
        app.openSessions()
        app.waitForText(SmokeFixtures.CommuteCar, substring = true)
        app.openSession(SmokeFixtures.CommuteCar)

        // No laps, said first; the route comes under it.
        app.waitForText("No laps", substring = true, timeoutMs = LoadTimeoutMs)
        app.scrollUntilShown(hasTestTag(DriveMapTag), LoadTimeoutMs)
        app.scrollUntilShown(hasText("DRIVE · WHOLE SESSION"))
        app.waitForText("Touch or drag along the line or the chart", substring = true)
        // The figures are drawn glyph by glyph (NumberText) and read as their content descriptions:
        // the drive moved, so its top speed is a number.
        app.waitFor(hasContentDescription(" km/h", substring = true))
        app.scrollUntilShown(hasText("slow to fast"))
        // Its speed along the drive.
        app.scrollUntilShown(hasText("SPEED, KM/H"))

        app.back()
        app.waitForText(SmokeFixtures.CommuteCar, substring = true)
    }
}
