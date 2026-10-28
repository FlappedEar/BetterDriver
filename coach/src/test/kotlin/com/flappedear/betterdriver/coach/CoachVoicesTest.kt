@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.coach

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoachVoicesTest {
    private fun v(name: String, tag: String, network: Boolean = false, installed: Boolean = true) =
        TtsVoice(name, tag, network, installed)

    private val phone = listOf(
        v("en-us-x-iol-local", "en-US"),
        v("en-gb-x-gbd-network", "en-GB", network = true),
        v("en-gb-x-gbc-local", "en-GB"),
        v("en-gb-x-gba-local", "en-GB"),
        v("en-au-x-aub-local", "en-AU", installed = false),
        v("pl-pl-x-oda-local", "pl-PL"),
        v("en-ng-x-tfn-local", "en-NG"),
        v("en-in-x-ena-local", "en-IN"),
    )

    @Test
    fun `only offline installed English voices, British first`() {
        assertEquals(
            listOf(
                "en-gb-x-gba-local", "en-gb-x-gbc-local", "en-us-x-iol-local", "en-in-x-ena-local", "en-ng-x-tfn-local",
            ),
            CoachVoices.usable(phone).map { it.name },
        )
    }

    @Test
    fun `in Polish only the Polish voices, labelled Polski`() {
        val polish = CoachVoices.usable(phone, CoachLanguage.Polish)
        assertEquals(listOf("pl-pl-x-oda-local"), polish.map { it.name })
        assertEquals("Polski · Voice 1", CoachVoices.label(polish.single(), phone))
        assertEquals("pl-pl-x-oda-local", CoachVoices.choose("pl-pl-x-oda-local", phone, CoachLanguage.Polish)?.name)
        assertNull(CoachVoices.choose("en-gb-x-gbc-local", phone, CoachLanguage.Polish), "an English voice is not kept")
        assertEquals(CoachLanguage.Polish, CoachVoices.languageOf("pl_PL"))
        assertEquals(CoachLanguage.English, CoachVoices.languageOf("de-DE"))
    }

    @Test
    fun `a saved voice is used only while it is usable`() {
        assertEquals("en-gb-x-gbc-local", CoachVoices.choose("en-gb-x-gbc-local", phone)?.name)
        assertNull(CoachVoices.choose("en-gb-x-gbd-network", phone))
        assertNull(CoachVoices.choose("en-au-x-aub-local", phone))
        assertNull(CoachVoices.choose("gone", phone))
        assertNull(CoachVoices.choose(null, phone))
    }

    @Test
    fun `voices are numbered within their accent`() {
        assertEquals(2, CoachVoices.number(v("en-gb-x-gbc-local", "en-GB"), phone))
        assertEquals(1, CoachVoices.number(v("en-us-x-iol-local", "en-US"), phone))
        assertNull(CoachVoices.number(v("en-gb-x-gbd-network", "en-GB", network = true), phone))
    }

    @Test
    fun `voices are labelled and grouped by accent`() {
        assertEquals("English (UK) · Voice 2", CoachVoices.label(v("en-gb-x-gbc-local", "en-GB"), phone))
        assertEquals("English (India) · Voice 1", CoachVoices.label(v("en-in-x-ena-local", "en-IN"), phone))
        assertNull(CoachVoices.label(v("gone", "en-GB"), phone))
        assertEquals(
            listOf("English (United Kingdom)" to 2, "English (United States)" to 1, "English (India)" to 1,
                "English (Nigeria)" to 1),
            CoachVoices.byAccent(phone).map { (accent, voices) -> accent to voices.size },
        )
    }

    @Test
    fun `speech settings are saved and read back`() {
        val file = Files.createTempDirectory("coach").resolve("settings.properties").toFile()
        val store = CoachSettingsStore(file)
        assertEquals(Speech(), store.read().speech)
        val s = CoachSettings(speech = Speech("en-gb-x-gbc-local", 1.2f, 0.9f, engine = "org.woheller69.ttsengine"))
        store.write(s)
        assertEquals(s, store.read())
        store.write(CoachSettings())
        assertEquals(Speech(), store.read().speech)
    }

    @Test
    fun `an out of range or damaged rate falls back`() {
        val file = Files.createTempDirectory("coach").resolve("settings.properties").toFile()
        file.writeText("speechRate=9\nspeechPitch=0.1\nttsVoice=\n")
        assertEquals(Speech(rate = Speech.MaxRate, pitch = Speech.MinPitch), CoachSettingsStore(file).read().speech)
        file.writeText("speechRate=NaN\n")
        assertEquals(Speech(), CoachSettingsStore(file).read().speech)
    }

    @Test
    fun `a settings file too large to read whole gives the defaults`() {
        val file = Files.createTempDirectory("coach").resolve("settings.properties").toFile()
        file.writeText("speechRate=1.2\nttsVoice=" + "x".repeat(CoachSettingsStore.MaxBytes.toInt()) + "\n")
        assertEquals(CoachSettings(), CoachSettingsStore(file).read())
    }
}
