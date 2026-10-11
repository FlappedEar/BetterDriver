package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup

/**
 * A GoPro in the equipment list, as the session cards need it: its name, and whether the app
 * downloads its files ([download], Open GoPro) or Arek adds them (a camera started by hand).
 */
data class ListedGoPro(val sourceId: String, val name: String, val download: Boolean)

/**
 * The GoPros in the equipment list, for the session cards: Open GoPro ones download, the others add
 * files. Sessions from before FBBD-105 name their camera [CameraIds.GOPRO_HERO11]; the first paired
 * HERO11 Black answers for it too, as in SetupRepo.goProFor, so those files stay downloadable.
 */
internal fun listedGoPros(setup: Setup): List<ListedGoPro> {
    val listed = setup.equipment.filter { it.model.isGoPro }.map {
        ListedGoPro(CameraIds.goPro(it.id), it.name, download = it.model == EquipmentModel.GOPRO)
    }
    val hero11 = setup.equipment.firstOrNull {
        it.model == EquipmentModel.GOPRO && it.address != null && it.goPro == GoProModel.HERO11_BLACK
    }
    return if (hero11 == null) listed else listed + ListedGoPro(CameraIds.GOPRO_HERO11, hero11.name, download = true)
}

/** Names for the cameras of sessions recorded before any number of GoPros could be listed (FBBD-105). */
internal fun legacyGoProName(sourceId: String): String = when (sourceId) {
    CameraIds.GOPRO_HERO11 -> "GoPro HERO11"
    CameraIds.GOPRO_HERO8 -> "GoPro HERO8"
    else -> "GoPro"
}
