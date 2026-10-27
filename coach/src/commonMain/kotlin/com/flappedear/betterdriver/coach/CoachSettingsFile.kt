package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.io.parseProperties
import com.flappedear.betterdriver.io.writeProperties

/**
 * The coach settings file's keys and values (FBBD-98), shared by Android ([CoachSettingsStore]) and
 * iOS, which write the same java.util.Properties file. A missing or damaged value gives its default.
 */
object CoachSettingsFile {
    private const val KeyVerbosity = "verbosity"
    private const val KeyDelay = "audioDelayMillis"
    private const val KeyLearning = "learningLaps"
    private const val KeyVoice = "voice"
    private const val KeyLanguage = "language"
    private const val KeyTtsVoice = "ttsVoice"
    private const val KeyRate = "speechRate"
    private const val KeyPitch = "speechPitch"
    private const val KeyEngine = "ttsEngine"

    fun fromMap(p: Map<String, String>): CoachSettings {
        val d = CoachSettings()
        return CoachSettings(
            verbosity = Verbosity.entries.firstOrNull { it.name == p[KeyVerbosity] } ?: d.verbosity,
            audioDelayMillis = p[KeyDelay]?.toIntOrNull()
                ?.coerceIn(0, CoachSettings.MaxAudioDelayMillis) ?: d.audioDelayMillis,
            learningLaps = p[KeyLearning]?.toIntOrNull()
                ?.coerceIn(0, CoachSettings.MaxLearningLaps) ?: d.learningLaps,
            voice = VoiceMode.entries.firstOrNull { it.name == p[KeyVoice] } ?: d.voice,
            language = CoachLanguage.entries.firstOrNull { it.name == p[KeyLanguage] } ?: d.language,
            speech = Speech(
                voiceName = p[KeyTtsVoice]?.takeIf { it.isNotBlank() },
                rate = p[KeyRate]?.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?.coerceIn(Speech.MinRate, Speech.MaxRate) ?: d.speech.rate,
                pitch = p[KeyPitch]?.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?.coerceIn(Speech.MinPitch, Speech.MaxPitch) ?: d.speech.pitch,
                engine = p[KeyEngine]?.takeIf { it.isNotBlank() },
            ),
        )
    }

    fun toMap(settings: CoachSettings): Map<String, String> = buildMap {
        put(KeyVerbosity, settings.verbosity.name)
        put(KeyDelay, settings.audioDelayMillis.toString())
        put(KeyLearning, settings.learningLaps.toString())
        put(KeyVoice, settings.voice.name)
        put(KeyLanguage, settings.language.name)
        settings.speech.voiceName?.let { put(KeyTtsVoice, it) }
        settings.speech.engine?.let { put(KeyEngine, it) }
        put(KeyRate, settings.speech.rate.toString())
        put(KeyPitch, settings.speech.pitch.toString())
    }

    /** The settings in a file's bytes; unreadable bytes give the defaults. */
    fun decode(bytes: ByteArray): CoachSettings = try {
        fromMap(parseProperties(bytes))
    } catch (_: IllegalArgumentException) {
        CoachSettings()
    }

    fun encode(settings: CoachSettings): ByteArray = writeProperties(toMap(settings))
}
