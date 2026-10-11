package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup

/**
 * A GoPro in the equipment list, as the session cards need it: its name, and whether the app
 * downloads its files ([download], Open GoPro) or Arek adds them (a camera started by hand).
 */
data class ListedGoPro(val sourceId: String, val name: String, val download: Boolean)

/** The GoPros in the equipment list, for the session cards: Open GoPro ones download, the others add files. */
internal fun listedGoPros(setup: Setup): List<ListedGoPro> = setup.equipment.filter { it.model.isGoPro }.map {
    ListedGoPro(CameraIds.goPro(it.id), it.name, download = it.model == EquipmentModel.GOPRO)
}

/** Names for the cameras of sessions recorded before any number of GoPros could be listed (FBBD-105). */
internal fun legacyGoProName(sourceId: String): String = when (sourceId) {
    CameraIds.GOPRO_HERO11 -> "GoPro HERO11"
    CameraIds.GOPRO_HERO8 -> "GoPro HERO8"
    else -> "GoPro"
}
