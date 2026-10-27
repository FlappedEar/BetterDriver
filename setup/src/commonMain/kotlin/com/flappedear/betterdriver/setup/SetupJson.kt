package com.flappedear.betterdriver.setup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Format name in the setup file, so a stray JSON file is never read as a setup. */
const val SetupFileFormat = "betterdriver-setup"

/**
 * The setup file version this build writes. Newer files are refused rather than half read.
 * Version 2 (FBBD-105) lists equipment by family; version 1 files are read through [upgradeV1].
 * Version 3 (FBBD-121, UI-105 C) lists OBD-II adapters as equipment; older files get the Wi-Fi
 * adapter the app used to look for by itself ([upgradeV2]).
 */
const val SetupFileVersion = 3

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

    /** Throws [SetupFormatException] for anything that is not a setup file of a known version. */
    @Suppress("ThrowsCount")
    fun decode(text: String): Setup = try {
        val root = json.parseToJsonElement(text).jsonObject
        if (root["format"]?.jsonPrimitive?.content != SetupFileFormat) throw SetupFormatException("not a setup file")
        val version = root["version"]?.jsonPrimitive?.int ?: throw SetupFormatException("no version")
        if (version > SetupFileVersion) throw SetupFormatException("setup file version $version is newer than this app")
        val setup = root["setup"] ?: throw SetupFormatException("no setup")
        val v2 = if (version < 2) upgradeV1(setup) else setup
        json.decodeFromJsonElement<Setup>(if (version < SetupFileVersion) upgradeV2(v2) else v2)
    } catch (e: SerializationException) {
        throw SetupFormatException("unreadable setup file", e)
    } catch (e: IllegalArgumentException) {
        throw SetupFormatException("unreadable setup file", e)
    }
}

/**
 * Version 1 named single products (FBBD-83): RACEBOX_MICRO for any RaceBox, GOPRO_HERO11 and
 * GOPRO_HERO8. Version 2 names families and keeps the camera in "goPro" (FBBD-105).
 */
private val V1Models = mapOf(
    "RACEBOX_MICRO" to (EquipmentModel.RACEBOX to null),
    "GOPRO_HERO11" to (EquipmentModel.GOPRO to GoProModel.HERO11_BLACK),
    "GOPRO_HERO8" to (EquipmentModel.GOPRO_FILES to GoProModel.HERO8_BLACK),
)

private fun upgradeV1(setup: JsonElement): JsonElement {
    val obj = setup as? JsonObject
    val items = obj?.get("equipment") as? JsonArray ?: return setup
    return JsonObject(obj + ("equipment" to JsonArray(items.map(::upgradeV1Item))))
}

/** The id of the Wi-Fi OBD-II adapter a version 2 setup gets ([upgradeV2]). */
internal const val UpgradedWifiObdId = "obd-wifi"

/**
 * Before version 3 the recorder looked for a Wi-Fi OBD-II adapter in every session without it being
 * listed (FBBD-15). Now only listed adapters are used (UI-105 C), so an older setup gets that
 * adapter listed and keeps recording car data as before; Arek can remove it.
 */
private fun upgradeV2(setup: JsonElement): JsonElement {
    val obj = setup as? JsonObject ?: return setup
    val items = obj["equipment"] as? JsonArray ?: JsonArray(emptyList())
    val wifi = json.encodeToJsonElement(
        Equipment(UpgradedWifiObdId, EquipmentModel.OBD_WIFI, EquipmentModel.OBD_WIFI.label, WifiObdAddress),
    )
    return JsonObject(obj + ("equipment" to JsonArray(items + wifi)))
}

private fun upgradeV1Item(item: JsonElement): JsonElement {
    val fields = item as? JsonObject
    val (model, goPro) = V1Models[fields?.get("model")?.jsonPrimitive?.content] ?: return item
    return JsonObject(
        fields.orEmpty() + buildMap {
            put("model", JsonPrimitive(model.name))
            if (goPro != null) put("goPro", JsonPrimitive(goPro.name))
        },
    )
}
