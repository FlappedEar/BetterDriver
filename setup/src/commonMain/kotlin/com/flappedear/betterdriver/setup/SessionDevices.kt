package com.flappedear.betterdriver.setup

// Drive's "This session" card (UI-308 A), the same on Android and iOS (FBBD-421): the devices the
// next session uses and how many of the listed cameras record. A card names devices; it does not
// say whether they answer.

/**
 * The names of the devices the next session uses: the receiver, the OBD-II adapters, the sensors
 * and the cameras turned on. [receiver] names the receiver when the app picks it at START (iOS)
 * rather than in the setup; null takes the setup's.
 */
fun sessionDeviceNames(setup: Setup, receiver: String?): List<String> {
    val active = setup.active()
    return listOfNotNull(receiver?.takeIf { it.isNotBlank() } ?: active.gnss?.name) +
        active.carData.map { it.name } +
        active.sensors.map { it.name } +
        active.cameras.map { it.name }
}

/** "device" or "devices": the unit after the card's count. */
fun devicesUnit(count: Int): String = if (count == 1) "device" else "devices"

/** A camera as Drive's card counts it: its [name] and whether it records the next session. */
data class CameraSwitch(val name: String, val on: Boolean)

/** How many of the listed cameras record this session: "GoPro HERO11 on", "2 of 3 cameras on". */
fun camerasLine(setup: Setup): String =
    camerasLine(setup.ofKind(EquipmentKind.CAMERA).map { CameraSwitch(it.name, it.id in setup.session.cameraIds) })

/**
 * How many of [cameras] record this session. iOS turns its phone camera on in START's sheet rather
 * than in the setup, so it hands its cameras over this way.
 */
fun camerasLine(cameras: List<CameraSwitch>): String {
    val on = cameras.count { it.on }
    return when {
        cameras.isEmpty() -> "No cameras"
        cameras.size == 1 -> if (on == 1) "${cameras[0].name} on" else "${cameras[0].name} off"
        else -> "$on of ${cameras.size} cameras on"
    }
}
