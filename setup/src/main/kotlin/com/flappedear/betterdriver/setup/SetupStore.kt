package com.flappedear.betterdriver.setup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Format name in the setup file, so a stray JSON file is never read as a setup. */
const val SetupFileFormat = "betterdriver-setup"

/** The setup file version this build writes. Newer files are refused rather than half read. */
const val SetupFileVersion = 1

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** Thrown when the setup file is not one this build can read. */
class SetupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object SetupJson {
    fun encode(setup: Setup): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "format" to json.encodeToJsonElement(SetupFileFormat),
                "version" to json.encodeToJsonElement(SetupFileVersion),
                "setup" to json.encodeToJsonElement(setup),
            ),
        ),
    )

    fun decode(text: String): Setup = try {
        val root = json.parseToJsonElement(text).jsonObject
        if (root["format"]?.jsonPrimitive?.content != SetupFileFormat) throw SetupFormatException("not a setup file")
        val version = root["version"]?.jsonPrimitive?.int ?: throw SetupFormatException("no version")
        if (version > SetupFileVersion) throw SetupFormatException("setup file version $version is newer than this app")
        json.decodeFromJsonElement<Setup>(root["setup"] ?: throw SetupFormatException("no setup"))
    } catch (e: SerializationException) {
        throw SetupFormatException("unreadable setup file", e)
    } catch (e: IllegalArgumentException) {
        throw SetupFormatException("unreadable setup file", e)
    }
}

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
 * The setup an existing install starts with. A paired RaceBox or HERO11 is listed and used. The
 * phone camera is listed and used unless it was switched off. A car becomes a profile only if
 * Arek chose one; the old default was not his choice. A HERO8 is not listed, because the old
 * build showed it to everyone.
 */
fun migrate(old: LegacySettings, newId: () -> String): Setup {
    var setup = Setup()
    old.raceBoxAddress?.takeIf { it.isNotBlank() }?.let {
        setup = setup.addEquipment(EquipmentModel.RACEBOX_MICRO, old.raceBoxName.orEmpty(), it, newId)
    }
    setup = setup.addEquipment(EquipmentModel.PHONE_CAMERA, newId = newId)
    if (old.phoneCamera == false) setup = setup.useCamera(setup.ofKind(EquipmentKind.CAMERA).first().id, false)
    old.goProAddress?.takeIf { it.isNotBlank() }?.let {
        setup = setup.addEquipment(EquipmentModel.GOPRO_HERO11, old.goProName.orEmpty(), it, newId)
    }
    old.car?.takeIf { it.isNotBlank() }?.let { setup = setup.addCar(it, newId = newId) }
    return setup
}
