package com.flappedear.betterdriver.shell

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
