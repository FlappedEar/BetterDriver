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

/**
 * Settings topics (UI-137 B): one short page lists them, each opens a page of its own. [Tracks] and
 * [Coach] open their existing pages.
 */
enum class SettingsTopic(val title: String) {
    Equipment("Equipment"),
    Cars("Cars"),
    Tracks("Tracks"),
    TrackScreen("Track screen"),
    Video("Video"),
    Coach("Spoken coach"),
    App("App"),
    Diagnostics("Diagnostics"),
}

/**
 * Whether an equipment item can be reached now (UI-138 B), as Settings › Equipment shows it while
 * it looks for Bluetooth devices. Only what the app can tell: a Bluetooth Classic device is not
 * heard by a scan, so it is only [Paired]; a Wi-Fi one is not looked for.
 */
enum class Reach(val label: String, val state: DeviceState?) {
    InRange("In range", DeviceState.Ready),
    NotSeen("Not seen", DeviceState.Off),
    BuiltIn("Built in", DeviceState.Ready),
    ByHand("Start by hand", DeviceState.Off),
    Paired("Paired", null),
    WiFi("Over Wi-Fi", null),
    MadeUp("Made-up data", null),

    /** While a session records the page does not look: the recorder holds the connections. */
    InSession("In this session", DeviceState.Ready),
}

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

/**
 * What a session row in Sessions says about it (UI-139 C), read with its laps (ReviewLaps): its timed
 * laps and the best one at the saved track it was recorded at, and whether car data (OBD-II) and heart
 * rate were recorded. Unknown until the session has been read.
 */
data class SessionFacts(
    val laps: Int = 0,
    val bestMillis: Long? = null,
    val obd: Boolean = false,
    val heartRate: Boolean = false,
)
