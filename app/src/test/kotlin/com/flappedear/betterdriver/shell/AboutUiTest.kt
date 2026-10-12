package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.timing.map.TileSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AboutUiTest {
    private fun section(title: String) = aboutSections().single { it.title == title }.credits

    @Test
    fun `the circuit directory is credited to Wikidata under CC0`() {
        val wikidata = section("Circuit directory").single()
        assertEquals("Wikidata", wikidata.name)
        assertEquals("CC0 1.0", wikidata.license)
    }

    @Test
    fun `map tiles carry exactly the attribution the map shows`() {
        assertEquals(
            listOf(TileSources.Orthophoto.attribution, TileSources.OpenStreetMap.attribution),
            section("Map tiles").map { it.detail },
        )
    }

    @Test
    fun `both bundled fonts are under the OFL`() {
        val fonts = section("Fonts")
        assertEquals(listOf("Barlow", "Saira Semi Condensed"), fonts.map { it.name })
        assertTrue(fonts.all { it.license == "SIL Open Font License 1.1" })
    }

    @Test
    fun `FlappedEar Telemetry is credited under Apache 2`() {
        val telemetry = section("Ported code").single()
        assertEquals("FlappedEar Telemetry", telemetry.name)
        assertEquals("Apache 2.0", telemetry.license)
    }

    @Test
    fun `every library's version matches the version catalog`() {
        // Unit tests run in the module's folder; the catalog is one level up.
        val catalog = File("../gradle/libs.versions.toml").readLines()
            .mapNotNull { Regex("""^(\w+)\s*=\s*"([^"]+)"""").find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
        OpenSourceLibraries.forEach { lib ->
            assertEquals(lib.name, catalog[lib.catalogKey], lib.version)
        }
        assertEquals(OpenSourceLibraries.map { it.name }, section("Open-source libraries").map { it.name })
    }
}
