package com.flappedear.betterdriver.recording

private const val ApiQ = 29
private const val ApiS = 31
private const val ApiU = 34

/** A foreground service type the recorder runs as (FBBD-34). */
enum class RecorderForegroundType { Location, ConnectedDevice }

/**
 * The foreground service types the recorder can claim on Android [sdk] without startForeground
 * throwing a SecurityException:
 * - None before Android 10, which has no types.
 * - Location: from Android 14 only while location (precise or approximate) is allowed; before it,
 *   always, as the recorder has always done.
 * - Connected device (the receiver and sensors over Bluetooth or Wi-Fi): on Android 12 and 13 only
 *   while nearby devices (BLUETOOTH_CONNECT) is allowed; before Android 12 there is no such
 *   permission, so always. From Android 14 always, as Android also accepts it for CHANGE_NETWORK_STATE
 *   and CHANGE_WIFI_STATE, which the manifest declares and are granted at install: a Wi-Fi receiver
 *   needs no runtime permission.
 *
 * Empty from Android 10 on means the recorder must not go foreground at all.
 */
fun recorderForegroundTypes(
    sdk: Int,
    locationGranted: Boolean,
    bluetoothConnectGranted: Boolean,
): Set<RecorderForegroundType> = buildSet {
    if (sdk >= ApiQ) {
        if (sdk < ApiU || locationGranted) add(RecorderForegroundType.Location)
        if (sdk < ApiS || sdk >= ApiU || bluetoothConnectGranted) add(RecorderForegroundType.ConnectedDevice)
    }
}
