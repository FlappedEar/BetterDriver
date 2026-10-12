package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListedGoProTest {
    private var n = 0
    private val ids = { "e${n++}" }

    @Test
    fun `a paired HERO11 also answers for sessions recorded before any number of GoPros`() {
        val s = Setup()
            .addEquipment(EquipmentModel.GOPRO, "Roof", "AA", ids, GoProModel.HERO11_BLACK)
            .addEquipment(EquipmentModel.GOPRO_FILES, "Old one", null, ids, GoProModel.HERO8_BLACK)
        assertEquals(
            listOf(
                ListedGoPro(CameraIds.goPro("e0"), "Roof", download = true),
                ListedGoPro(CameraIds.goPro("e1"), "Old one", download = false, CameraIds.GOPRO_HERO8),
                ListedGoPro(CameraIds.GOPRO_HERO11, "Roof", download = true),
            ),
            listedGoPros(s),
        )
    }

    @Test
    fun `no legacy entry without a paired HERO11`() {
        val s = Setup().addEquipment(EquipmentModel.GOPRO, "Roof", "AA", ids, GoProModel.HERO13_BLACK)
        assertEquals(listOf(CameraIds.goPro("e0")), listedGoPros(s).map { it.sourceId })
    }

    @Test
    fun `the first HERO8 started by hand answers for the single HERO8 of older sessions`() {
        val s = Setup()
            .addEquipment(EquipmentModel.GOPRO_FILES, "Rear", null, ids, GoProModel.HERO7_BLACK)
            .addEquipment(EquipmentModel.GOPRO_FILES, "Old one", null, ids, GoProModel.HERO8_BLACK)
            .addEquipment(EquipmentModel.GOPRO_FILES, "Spare", null, ids, GoProModel.HERO8_BLACK)
        val listed = listedGoPros(s)
        assertEquals(listOf("e0", "e1", "e2").map(CameraIds::goPro), listed.map { it.sourceId })
        assertEquals(listOf("Old one"), listed.filter { it.answersFor(CameraIds.GOPRO_HERO8) }.map { it.name })
        assertTrue(listed[1].answersFor(CameraIds.goPro("e1")))
    }
}
