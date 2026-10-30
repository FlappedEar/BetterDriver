package com.flappedear.betterdriver.setup

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Arek's setup as one JSON file (FBBD-83). Writes go to a temporary file that is synced and then
 * renamed over the old one, so a crash leaves either the old or the new setup. A file that cannot
 * be read is moved aside, never overwritten, and [load] returns null so the caller starts again
 * from the old settings or a fresh setup. Shared by the Android app and the iOS app (FBBD-359).
 *
 * Not thread-safe; the app keeps one instance and calls it off the main thread.
 */
class SetupStore(private val file: String) {

    /** The saved setup, or null when there is none or it could not be read. */
    fun load(): Setup? {
        if (!CoreFiles.isFile(file)) return null
        return try {
            SetupJson.decode(CoreFiles.readText(file))
        } catch (_: SetupFormatException) {
            setAside()
        } catch (_: IOException) {
            setAside()
        }
    }

    /** Saves [setup]; throws [IOException] when it cannot. */
    @Throws(IOException::class)
    fun save(setup: Setup) {
        CoreFiles.writeTextAtomically(file, SetupJson.encode(setup))
    }

    /** Files moved aside because they could not be read. */
    fun setAsideFiles(): List<String> {
        val dir = file.trimEnd('/').substringBeforeLast('/', ".")
        val name = file.trimEnd('/').substringAfterLast('/')
        return CoreFiles.list(dir).filter { it.startsWith(name + UnreadableMarker) }.map { CoreFiles.join(dir, it) }
    }

    @OptIn(ExperimentalTime::class)
    private fun setAside(): Setup? {
        CoreFiles.move(file, file + UnreadableMarker + Clock.System.now().toEpochMilliseconds())
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
