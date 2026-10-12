package com.flappedear.betterdriver.shell

/**
 * What stops working when a permission is denied (FBBD-35), one line per group as Android's own
 * permission screen groups them. Plain strings, so this stays testable without Android: they are
 * the values of the Manifest.permission constants.
 */
enum class PermissionGroup(val title: String, val effect: String, val permissions: Set<String>) {
    Location(
        "Location",
        "no GNSS from the phone; Bluetooth GPS receivers also need it",
        setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    ),
    NearbyDevices(
        "Nearby devices",
        "no RaceBox, GoPro or Bluetooth receivers",
        setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
    ),
    Camera("Camera", "no phone video", setOf("android.permission.CAMERA")),
    Microphone("Microphone", "video without sound", setOf("android.permission.RECORD_AUDIO")),
    Notifications("Notifications", "no recording notification", setOf("android.permission.POST_NOTIFICATIONS")),
}

/** "Camera off: no phone video". */
fun PermissionGroup.deniedLine(): String = "$title off: $effect"

/**
 * The groups with at least one of [denied] in them, in the order of [PermissionGroup], each once.
 * Permissions outside the groups are left out.
 */
fun deniedGroups(denied: Collection<String>): List<PermissionGroup> =
    PermissionGroup.entries.filter { g -> denied.any { it in g.permissions } }
