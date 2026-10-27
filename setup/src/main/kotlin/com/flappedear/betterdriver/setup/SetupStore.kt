package com.flappedear.betterdriver.setup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Arek's setup as one JSON file (FBBD-83). Writes go to a temporary file that is synced and then
 * renamed over the old one, so a crash leaves either the old or the new setup. A file that cannot
 * be read is moved aside, never overwritten, and [load] returns null so the caller starts again
 * from the old settings or a fresh setup.
 *
 * Not thread-safe; the app keeps one instance and calls it off the main thread.
 */
class SetupStore(private val file: File) {

    /** The saved setup, or null when there is none or it could not be read. */
    fun load(): Setup? {
        if (!file.isFile) return null
        return try {
            SetupJson.decode(file.readText())
        } catch (_: SetupFormatException) {
            setAside()
        } catch (_: IOException) {
            setAside()
        }
    }

    fun save(setup: Setup) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(SetupJson.encode(setup).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Files moved aside because they could not be read. */
    fun setAsideFiles(): List<File> =
        file.parentFile?.listFiles { f -> f.name.startsWith(file.name + UnreadableMarker) }.orEmpty().toList()

    private fun setAside(): Setup? {
        file.renameTo(File(file.path + UnreadableMarker + System.currentTimeMillis()))
        return null
    }

    private companion object {
        const val UnreadableMarker = ".unreadable-"
    }
}

/**
 * Settings from builds before FBBD-83, which assumed Arek's hardware and cars. [phoneCamera] and
 * [car] are null when they were never set, so a default is not mistaken for his choice.
 */
data class LegacySettings(
    val raceBoxAddress: String? = null,
    val raceBoxName: String? = null,
    val goProAddress: String? = null,
    val goProName: String? = null,
    val phoneCamera: Boolean? = null,
    val car: String? = null,
)

/**
 * The setup an existing install starts with. A paired RaceBox or HERO11 (the only GoPro the old
 * build could pair) is listed and used. The
 * phone camera is listed and used unless it was switched off. A car becomes a profile only if
 * Arek chose one; the old default was not his choice. A HERO8 is not listed, because the old
 * build showed it to everyone.
 */
fun migrate(old: LegacySettings, newId: () -> String): Setup {
    var setup = Setup()
    old.raceBoxAddress?.takeIf { it.isNotBlank() }?.let {
        setup = setup.addEquipment(EquipmentModel.RACEBOX, old.raceBoxName.orEmpty(), it, newId)
    }
    setup = setup.addEquipment(EquipmentModel.PHONE_CAMERA, newId = newId)
    if (old.phoneCamera == false) setup = setup.useCamera(setup.ofKind(EquipmentKind.CAMERA).first().id, false)
    old.goProAddress?.takeIf { it.isNotBlank() }?.let {
        setup = setup.addEquipment(EquipmentModel.GOPRO, old.goProName.orEmpty(), it, newId, GoProModel.HERO11_BLACK)
    }
    old.car?.takeIf { it.isNotBlank() }?.let { setup = setup.addCar(it, newId = newId) }
    return setup
}
