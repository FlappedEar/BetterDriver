package com.flappedear.betterdriver.setup

/** Which live page an equipment item's page links to (FBBD-440), the same on Android and the iPhone. */
enum class LivePage {
    /** The device's own recorder source or link, run outside a session: every value it sends. */
    SOURCE,

    /** RaceBox diagnostics (FBBD-89): its live values, the read-only queries and calibration. */
    DIAGNOSTICS,

    /** The car data page (FBBD-261): the adapter's connection and the car's values, FR-S CAN too. */
    CAR_DATA,

    /** The camera test (FBBD-278): what the phone camera records. */
    CAMERA_TEST,

    /** An Open GoPro camera's status over Bluetooth: battery, SD card, recording. Read only. */
    GOPRO,

    /** Nothing to connect to: a camera whose files are added after the session. */
    NOTHING,
}

/**
 * The Equipment pages' live data (FBBD-440, Arek 2026-11-03: test each device and see its data live
 * without a session). Each model's page has one row to its live page; RaceBox keeps its Diagnostics.
 */
object EquipmentLive {
    /** The row's label on an item's page; a RaceBox's row keeps its name, Diagnostics. */
    const val ROW = "Live data"

    /** The row's value, as the RaceBox's Diagnostics row shows it. */
    const val ROW_VALUE = "Live"

    @Suppress("CyclomaticComplexMethod") // One branch per equipment model.
    fun page(model: EquipmentModel): LivePage = when (model) {
        EquipmentModel.RACEBOX -> LivePage.DIAGNOSTICS
        EquipmentModel.NMEA_GNSS, EquipmentModel.LNS_GNSS, EquipmentModel.DIY_GNSS, EquipmentModel.TCP_GNSS,
        EquipmentModel.VBOX_SPORT, EquipmentModel.DRAGY, EquipmentModel.PHONE_GPS, EquipmentModel.DEMO_GNSS,
        EquipmentModel.DEMO_TRACK, EquipmentModel.HEART_RATE, EquipmentModel.TPMS_BRIDGE,
        -> LivePage.SOURCE
        EquipmentModel.OBD_BLUETOOTH, EquipmentModel.OBD_WIFI -> LivePage.CAR_DATA
        EquipmentModel.PHONE_CAMERA -> LivePage.CAMERA_TEST
        EquipmentModel.GOPRO -> LivePage.GOPRO
        EquipmentModel.GOPRO_FILES, EquipmentModel.CAMERA_FILES -> LivePage.NOTHING
    }

    /** The row's label for [model], or null when its page has none. */
    fun rowLabel(model: EquipmentModel): String? = when (page(model)) {
        LivePage.NOTHING -> null
        LivePage.DIAGNOSTICS -> "Diagnostics"
        else -> ROW
    }
}
