package com.flappedear.betterdriver.faults

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.flappedear.betterdriver.shell.PermissionGroup
import com.flappedear.betterdriver.shell.deniedLine
import com.flappedear.betterdriver.uikill.UiKillPhase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The runtime permissions flow on the emulator (FBBD-35), in phases run by
 * .github/scripts/permissions.sh, which revokes and grants with `pm` between them: Android ends the
 * app's processes when a permission is revoked, so no test inside the app can do it.
 *
 * Each denial must say what will not work, with no crash: a required one (location, nearby devices)
 * on setup's checklist after Android's own dialog is answered "Don't allow", an optional one (camera,
 * notifications) under Settings › App › Permissions. A permission revoked in system settings while the
 * app is open is shown when the app comes back.
 *
 * No permission rule here: the script decides what is granted.
 */
@RunWith(AndroidJUnit4::class)
@UiKillPhase
class PermissionTest {
    private val d = AppDriver()

    /** The permission group the script revoked, by [PermissionGroup] name. */
    private val group: PermissionGroup
        get() = PermissionGroup.valueOf(
            checkNotNull(InstrumentationRegistry.getArguments().getString("group")) { "no -e group" },
        )

    @Test
    fun seed() {
        d.seedDemo(CarName)
    }

    /** The script revoked [group]: opening the app explains what will not work without it. */
    @Test
    fun deniedIsExplained() {
        val revoked = group
        d.openApp()
        if (revoked in Required) {
            // Without a required permission the app opens on setup; Allow asks, and the driver says no.
            d.tap("Allow")
            denyDialogs()
        } else {
            openPermissionsRow()
        }
        waitForLine(revoked)
        assertEquals(d.app.packageName, d.device.currentPackageName)
        d.checkNoAppErrors()
    }

    /**
     * Settings › App with every permission allowed, left open for the script to revoke one. In the
     * app the script opened: the runner would finish an activity this phase started when it ends.
     */
    @Test
    fun openPermissions() {
        openPermissionsRow()
        checkNotNull(d.device.wait(Until.findObject(By.text("All allowed")), AppDriver.LoadTimeoutMs)) {
            "Permissions does not say \"All allowed\". ${d.screen()}"
        }
    }

    /** The script revoked [group] while the app was open, then brought the app back. */
    @Test
    fun revokeSeenOnResume() {
        val revoked = group
        val line = revoked.deniedLine()
        val inPlace = d.device.wait(Until.hasObject(By.text(line)), InPlaceMs) == true
        if (!inPlace) {
            // Android ended the process for the revoke and opened the app afresh: look where it says.
            Log.i(Tag, "the app came back elsewhere: ${d.screen()}")
            d.openApp()
            openPermissionsRow()
        }
        waitForLine(revoked)
        Log.i(Tag, "revoked ${revoked.name} shown on resume, in place: $inPlace")
        d.checkNoAppErrors()
    }

    private fun openPermissionsRow() {
        d.tap("Settings")
        d.tap("App")
        checkNotNull(d.device.wait(Until.findObject(By.text("Permissions")), AppDriver.LoadTimeoutMs)) {
            "no Permissions row. ${d.screen()}"
        }
    }

    private fun waitForLine(group: PermissionGroup) {
        val line = group.deniedLine()
        checkNotNull(d.device.wait(Until.findObject(By.text(line)), AppDriver.LoadTimeoutMs)) {
            "no \"$line\" for ${group.name} denied. ${d.screen()}"
        }
    }

    /** Answers each of Android's permission dialogs with "Don't allow" until no more come. */
    private fun denyDialogs() {
        var denied = 0
        while (denied < MaxDialogs) {
            val deny = d.device.wait(Until.findObject(By.res(DenyButton)), DialogTimeoutMs) ?: break
            deny.click()
            denied++
        }
        // None when Android no longer asks (denied for good): the app then gets the denial at once.
        Log.i(Tag, "denied $denied permission dialogs")
        d.device.wait(Until.hasObject(By.pkg(d.app.packageName).depth(0)), AppDriver.LoadTimeoutMs)
    }

    private companion object {
        const val Tag = "PermissionTest"
        const val CarName = "Permission test car"
        val Required = setOf(PermissionGroup.Location, PermissionGroup.NearbyDevices)

        /** "Don't allow" and "Don't allow, don't ask again" of Android's permission dialog. */
        val DenyButton: Pattern = Pattern.compile("com\\.android\\.permissioncontroller:id/permission_deny.*button")
        const val MaxDialogs = 6
        const val DialogTimeoutMs = 5_000L
        const val InPlaceMs = 10_000L
    }
}
