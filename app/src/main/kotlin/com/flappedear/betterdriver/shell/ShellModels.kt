package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.obd.ObdStatus
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup

/** How ready a device is: green when ready, amber when it needs attention, grey when off or missing. */
enum class DeviceState { Ready, Attention, Off }

/** One line of the first-run checklist. [notes] are extra lines under it, such as what a denied permission stops. */
data class SetupStep(
    val title: String,
    val detail: String,
    val done: Boolean,
    val action: String?,
    val notes: List<String> = emptyList(),
    val onAction: () -> Unit,
)

/** A RaceBox or Open GoPro camera heard nearby while adding equipment (UI-34 B). [bars] is 0 to 4. */
data class NearbyDevice(val model: EquipmentModel, val name: String, val address: String, val bars: Int)

/** What the session card on home can change (UI-36 A). [update] applies a change to the setup and saves it. */
data class SessionCardActions(
    val update: ((Setup) -> Setup) -> Unit = {},
    val onAddCar: () -> Unit = {},
    val onAddReceiver: () -> Unit = {},
)

/** Settings tabs (UI-15 option B). */
enum class SettingsTab(val title: String) { Devices("Devices"), Car("Car"), Recording("Recording"), App("App") }

/**
 * How the OBD page and the Settings row name a listed adapter (FBBD-121, UI-106 A): [name] as
 * Android shows it and [via] how it is reached, "vLinker MC+ · Bluetooth".
 */
data class ObdAdapterLabel(val name: String, val via: String) {
    val text: String get() = "$name · $via"
    val wifi: Boolean get() = via == WifiVia

    companion object {
        const val WifiVia = "Wi-Fi"
    }
}

/**
 * What the OBD page shows (FBBD-261): whether a session records, and its OBD-II connection; status is
 * null when nothing reads the car: no adapter is listed ([adapter] null), or the recording has no OBD
 * source (Demo GNSS). [adapter] is the first listed adapter (UI-105 C).
 */
data class ObdView(val recording: Boolean, val status: ObdStatus?, val adapter: ObdAdapterLabel? = null)
