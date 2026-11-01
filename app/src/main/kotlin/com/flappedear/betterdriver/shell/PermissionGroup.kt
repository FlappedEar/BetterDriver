package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.setup.PermissionLine

/**
 * What stops working when a permission is denied (FBBD-35), one line per group as Android's own
 * permission screen groups them. Plain strings, so this stays testable without Android: they are
 * the values of the Manifest.permission constants.
 */
enum class PermissionGroup(val line: PermissionLine, val permissions: Set<String>) {
    Location(
        PermissionLine.LOCATION,
        setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    ),
    NearbyDevices(
        PermissionLine.NEARBY_DEVICES,
        setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
    ),
    Camera(PermissionLine.CAMERA, setOf("android.permission.CAMERA")),
    Microphone(PermissionLine.MICROPHONE, setOf("android.permission.RECORD_AUDIO")),
    Notifications(PermissionLine.NOTIFICATIONS, setOf("android.permission.POST_NOTIFICATIONS")),
    ;

    /** The texts are the shared ones (FBBD-371), so iOS says the same. */
    val title: String get() = line.title
    val effect: String get() = line.effect
}

/** "Camera off: no phone video". */
fun PermissionGroup.deniedLine(): String = line.deniedLine

/**
 * The groups with at least one of [denied] in them, in the order of [PermissionGroup], each once.
 * Permissions outside the groups are left out.
 */
fun deniedGroups(denied: Collection<String>): List<PermissionGroup> =
    PermissionGroup.entries.filter { g -> denied.any { it in g.permissions } }
