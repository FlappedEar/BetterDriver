package com.flappedear.betterdriver.coach

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/**
 * Coach settings in one small file (FBBD-98). The app writes it; the recorder and coach processes
 * read it at each session start, so a change applies from the next session. A missing or damaged
 * file gives the defaults.
 */
class CoachSettingsStore(private val file: File) {
    fun read(): CoachSettings {
        val p = Properties()
        try {
            if (file.isFile) file.inputStream().use(p::load)
        } catch (_: IOException) {
            p.clear()
        } catch (_: IllegalArgumentException) {
            p.clear()
        }
        val d = CoachSettings()
        return CoachSettings(
            verbosity = Verbosity.entries.firstOrNull { it.name == p.getProperty(KeyVerbosity) } ?: d.verbosity,
            audioDelayMillis = p.getProperty(KeyDelay)?.toIntOrNull()
                ?.coerceIn(0, CoachSettings.MaxAudioDelayMillis) ?: d.audioDelayMillis,
            learningLaps = p.getProperty(KeyLearning)?.toIntOrNull()
                ?.coerceIn(0, CoachSettings.MaxLearningLaps) ?: d.learningLaps,
            voice = VoiceMode.entries.firstOrNull { it.name == p.getProperty(KeyVoice) } ?: d.voice,
            speech = Speech(
                voiceName = p.getProperty(KeyTtsVoice)?.takeIf { it.isNotBlank() },
                rate = p.getProperty(KeyRate)?.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?.coerceIn(Speech.MinRate, Speech.MaxRate) ?: d.speech.rate,
                pitch = p.getProperty(KeyPitch)?.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?.coerceIn(Speech.MinPitch, Speech.MaxPitch) ?: d.speech.pitch,
            ),
        )
    }

    fun write(settings: CoachSettings) {
        file.parentFile?.mkdirs()
        val p = Properties()
        p.setProperty(KeyVerbosity, settings.verbosity.name)
        p.setProperty(KeyDelay, settings.audioDelayMillis.toString())
        p.setProperty(KeyLearning, settings.learningLaps.toString())
        p.setProperty(KeyVoice, settings.voice.name)
        settings.speech.voiceName?.let { p.setProperty(KeyTtsVoice, it) }
        p.setProperty(KeyRate, settings.speech.rate.toString())
        p.setProperty(KeyPitch, settings.speech.pitch.toString())
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            p.store(out, "BetterDriver coach settings")
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val KeyVerbosity = "verbosity"
        const val KeyDelay = "audioDelayMillis"
        const val KeyLearning = "learningLaps"
        const val KeyVoice = "voice"
        const val KeyTtsVoice = "ttsVoice"
        const val KeyRate = "speechRate"
        const val KeyPitch = "speechPitch"
    }
}
