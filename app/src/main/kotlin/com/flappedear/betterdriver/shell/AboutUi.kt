package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.timing.map.TileSource
import com.flappedear.betterdriver.timing.map.TileSources

/** One credit on the About and credits page (FBBD-79). [url], when set, opens in the browser. */
data class Credit(val name: String, val detail: String, val license: String, val url: String? = null)

/** A titled group of [credits]. */
data class CreditSection(val title: String, val credits: List<Credit>)

/**
 * An open-source library the app ships, with its version in gradle/libs.versions.toml under
 * [catalogKey] (a test keeps the two in step).
 */
data class OpenSourceLibrary(val name: String, val catalogKey: String, val version: String, val url: String)

private const val Apache2 = "Apache 2.0"
private const val Ofl = "SIL Open Font License 1.1"
private const val OflUrl = "https://openfontlicense.org"

/** The libraries in the app, from the version catalog. Build and test tools are left out. */
val OpenSourceLibraries = listOf(
    OpenSourceLibrary("Kotlin", "kotlin", "2.2.20", "https://kotlinlang.org"),
    OpenSourceLibrary("kotlinx.coroutines", "coroutines", "1.10.2", "https://github.com/Kotlin/kotlinx.coroutines"),
    OpenSourceLibrary(
        "kotlinx.serialization",
        "kotlinxSerialization",
        "1.9.0",
        "https://github.com/Kotlin/kotlinx.serialization",
    ),
    OpenSourceLibrary(
        "Jetpack Compose and Material 3",
        "composeBom",
        "2025.09.00",
        "https://developer.android.com/compose",
    ),
    OpenSourceLibrary(
        "AndroidX Core",
        "coreKtx",
        "1.16.0",
        "https://developer.android.com/jetpack/androidx/releases/core",
    ),
    OpenSourceLibrary(
        "AndroidX Activity",
        "activityCompose",
        "1.10.1",
        "https://developer.android.com/jetpack/androidx/releases/activity",
    ),
    OpenSourceLibrary(
        "AndroidX Lifecycle",
        "lifecycle",
        "2.9.2",
        "https://developer.android.com/jetpack/androidx/releases/lifecycle",
    ),
    OpenSourceLibrary(
        "AndroidX Navigation",
        "navigation",
        "2.9.0",
        "https://developer.android.com/jetpack/androidx/releases/navigation",
    ),
    OpenSourceLibrary("CameraX", "camerax", "1.4.2", "https://developer.android.com/jetpack/androidx/releases/camera"),
    OpenSourceLibrary("Media3", "media3", "1.8.0", "https://github.com/androidx/media"),
    OpenSourceLibrary("Guava", "guava", "33.4.8-android", "https://github.com/google/guava"),
)

/** Map tiles, credited exactly as the map shows them (TileLoader draws [TileSource.attribution]). */
private fun tileCredit(source: TileSource, license: String, url: String) =
    Credit(source.name, source.attribution, license, url)

/** Everything on the About and credits page below the version, in order. */
fun aboutSections(): List<CreditSection> = listOf(
    CreditSection(
        "Circuit directory",
        listOf(
            Credit(
                "Wikidata",
                "Circuit names and positions, used to name a session's track",
                "CC0 1.0",
                "https://www.wikidata.org",
            ),
        ),
    ),
    CreditSection(
        "Map tiles",
        listOf(
            tileCredit(TileSources.Orthophoto, "GUGiK terms of use", "https://www.geoportal.gov.pl"),
            tileCredit(TileSources.OpenStreetMap, "ODbL 1.0", "https://www.openstreetmap.org/copyright"),
        ),
    ),
    CreditSection(
        "Fonts",
        listOf(
            Credit("Barlow", "Copyright 2017 The Barlow Project Authors", Ofl, "https://github.com/jpt/barlow"),
            Credit("Saira Semi Condensed", "Copyright 2016 The Saira Project Authors", Ofl, OflUrl),
        ),
    ),
    CreditSection(
        "Ported code",
        listOf(
            Credit(
                "FlappedEar Telemetry",
                "The lap analysis and the coach logic are ported from it",
                Apache2,
                "https://github.com/FlappedEar/Telemetry",
            ),
        ),
    ),
    CreditSection(
        "Open-source libraries",
        OpenSourceLibraries.map { Credit(it.name, it.version, Apache2, it.url) },
    ),
)
