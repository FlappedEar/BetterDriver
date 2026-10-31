package com.flappedear.betterdriver.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The first-run checklist reads the same on Android and iOS (FBBD-371). */
class FirstRunSetupTest {
    private var next = 0
    private fun id() = "id${next++}"

    private val none = PermissionsState(requiredGranted = false, allGranted = false)

    @Test
    fun aFreshInstallListsTheFourStepsInAndroidsOrder() {
        val steps = FirstRunSetup.steps(Setup.fresh(::id), none)
        assertEquals(
            listOf(FirstRunStepKind.PERMISSIONS, FirstRunStepKind.GNSS, FirstRunStepKind.CAMERAS, FirstRunStepKind.CAR),
            steps.map { it.kind },
        )
        assertEquals(listOf("Permissions", "GNSS receiver", "Cameras", "Car"), steps.map { it.title })
        assertEquals(
            listOf(FirstRunSetup.PERMISSIONS_DETAIL, "Needed for lap timing", "Phone camera", "Optional"),
            steps.map { it.detail },
        )
        assertEquals(listOf(false, false, true, false), steps.map { it.done })
        assertEquals(listOf("Allow", "Add", "Set up", "Add"), steps.map { it.action })
        assertFalse(FirstRunSetup.canFinish(steps))
        assertEquals(
            "1 of 4 done. You can start without the optional steps.",
            FirstRunSetup.summary(steps.count { it.done }, steps.size),
        )
    }

    @Test
    fun onlyTheRequiredPermissionsAreNeededToFinish() {
        val steps = FirstRunSetup.steps(Setup.fresh(::id), PermissionsState(requiredGranted = true, allGranted = false))
        assertTrue(FirstRunSetup.canFinish(steps))
        assertEquals("Allow", steps[0].action)
        val every = FirstRunSetup.steps(Setup.fresh(::id), PermissionsState(requiredGranted = true, allGranted = true))
        assertNull(every[0].action)
    }

    @Test
    fun aBlockedPermissionSendsToSettingsAndSaysWhatStops() {
        val state = PermissionsState(
            requiredGranted = true,
            allGranted = false,
            blocked = true,
            denied = listOf(PermissionLine.CAMERA, PermissionLine.MICROPHONE),
        )
        val step = FirstRunSetup.steps(Setup.fresh(::id), state)[0]
        assertEquals("Settings", step.action)
        assertEquals(listOf("Camera off: no phone video", "Microphone off: video without sound"), step.notes)
    }

    @Test
    fun whatIsSetUpShowsByName() {
        val setup = Setup.fresh(::id)
            .addEquipment(EquipmentModel.RACEBOX, "RaceBox Micro 3241", "E2C5", ::id)
            .addCar("Scion FR-S", "6AT", ::id)
        val steps = FirstRunSetup.steps(setup, none)
        assertEquals("RaceBox Micro 3241", steps[1].detail)
        assertTrue(steps[1].done)
        assertNull(steps[1].action)
        assertEquals("Scion FR-S", steps[3].detail)
        assertEquals("Choose", steps[3].action)
    }

    @Test
    fun noCameraIsOptional() {
        val setup = Setup.fresh(::id)
        val phone = setup.equipment.single()
        val steps = FirstRunSetup.steps(setup.useCamera(phone.id, false), none)
        assertEquals("None (optional)", steps[2].detail)
        assertFalse(steps[2].done)
    }

    @Test
    fun aTabletNeverOpensOnSetupAndAPhoneUntilDoneWithPermissions() {
        assertFalse(FirstRunSetup.opensOnSetup(tablet = true, setupDone = false, requiredGranted = false))
        assertTrue(FirstRunSetup.opensOnSetup(tablet = false, setupDone = false, requiredGranted = true))
        assertTrue(FirstRunSetup.opensOnSetup(tablet = false, setupDone = true, requiredGranted = false))
        assertFalse(FirstRunSetup.opensOnSetup(tablet = false, setupDone = true, requiredGranted = true))
    }
}
