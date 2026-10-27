package com.flappedear.betterdriver.coach

import kotlin.test.Test
import kotlin.test.assertEquals

/** The coach settings file reads the same on Android and iOS (FBBD-338). */
class CoachSettingsFileTest {
    @Test
    fun settingsSurviveAWriteAndARead() {
        val s = CoachSettings(
            verbosity = Verbosity.Full,
            audioDelayMillis = 300,
            learningLaps = 1,
            voice = VoiceMode.PreRendered,
            language = CoachLanguage.Polish,
            speech = Speech(voiceName = "com.apple.voice.compact.pl-PL.Zosia", rate = 1.2f, pitch = 0.9f),
        )
        assertEquals(s, CoachSettingsFile.decode(CoachSettingsFile.encode(s)))
    }

    @Test
    fun aFileWrittenByAndroidReads() {
        val android = "#BetterDriver coach settings\n#Tue Oct 27 15:00:00 CET 2026\nverbosity=LapTimes\n" +
            "audioDelayMillis=200\nlearningLaps=5\nvoice=Live\nlanguage=English\nspeechRate=1.1\nspeechPitch=1.0\n"
        val s = CoachSettingsFile.decode(android.encodeToByteArray())
        assertEquals(Verbosity.LapTimes, s.verbosity)
        assertEquals(200, s.audioDelayMillis)
        assertEquals(5, s.learningLaps)
        assertEquals(1.1f, s.speech.rate)
    }

    @Test
    fun valuesOutOfRangeAreClampedAndUnknownOnesDefault() {
        val s = CoachSettingsFile.fromMap(
            mapOf("audioDelayMillis" to "9000", "learningLaps" to "-2", "verbosity" to "Loud", "speechRate" to "NaN"),
        )
        assertEquals(CoachSettings.MaxAudioDelayMillis, s.audioDelayMillis)
        assertEquals(0, s.learningLaps)
        assertEquals(CoachSettings().verbosity, s.verbosity)
        assertEquals(1f, s.speech.rate)
    }

    @Test
    fun emptyBytesGiveTheDefaults() {
        assertEquals(CoachSettings(), CoachSettingsFile.decode(ByteArray(0)))
    }
}
