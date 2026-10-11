package com.flappedear.betterdriver.shell

/** How ready a device is: green when ready, amber when it needs attention, grey when off or missing. */
enum class DeviceState { Ready, Attention, Off }

/** One device in the ready check: RaceBox, phone camera or a GoPro. */
data class DeviceStatus(val name: String, val shortName: String, val detail: String, val state: DeviceState)

/** One line of the first-run checklist. */
data class SetupStep(
    val title: String,
    val detail: String,
    val done: Boolean,
    val action: String?,
    val onAction: () -> Unit,
)

/** A RaceBox found nearby. [bars] is signal strength from 0 to 4. */
data class PairCandidate(val name: String, val serial: String, val bars: Int, val address: String = "")

/** The RaceBox saved at pairing: its advertised name and Bluetooth address. */
data class PairedRaceBox(val name: String, val address: String) {
    /** The serial part of the advertised name, e.g. "3241". */
    val serial: String get() = name.substringAfterLast(' ', "")
}

/** Settings tabs (UI-15 option B). */
enum class SettingsTab(val title: String) { Devices("Devices"), Car("Car"), Recording("Recording"), App("App") }
