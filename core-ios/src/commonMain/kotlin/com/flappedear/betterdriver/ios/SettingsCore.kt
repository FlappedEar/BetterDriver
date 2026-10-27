package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.Advice
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CoachSettingsFile
import com.flappedear.betterdriver.coach.Corner
import com.flappedear.betterdriver.coach.PhraseBook
import com.flappedear.betterdriver.coach.Speech
import com.flappedear.betterdriver.coach.Turn
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.VoiceMode
import com.flappedear.betterdriver.setup.EquipmentKind
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupFormatException
import com.flappedear.betterdriver.setup.SetupJson

/**
 * The iPhone's Settings (FBBD-338) on the Android model: the same setup.json ([Setup], FBBD-83) and
 * coach settings file ([CoachSettings], FBBD-98), read and written as text by the app, and the
 * changes Settings makes, with the ids the app gives new items. Kotlin defaults and enum lists do
 * not reach Swift, so they are spelled out here.
 */
@Suppress("TooManyFunctions") // One small call per thing Settings changes.
object SettingsCore {
    /** A fresh install: the phone camera only, as on Android. */
    fun freshSetup(id: String): Setup = Setup.fresh { id }

    /** The setup in [text], or null when it is not a setup file this build can read. */
    fun decodeSetup(text: String): Setup? = try {
        SetupJson.decode(text)
    } catch (_: SetupFormatException) {
        null
    }

    fun encodeSetup(setup: Setup): String = SetupJson.encode(setup)

    /** The coach settings in a settings.properties file's [text]; null or unreadable gives the defaults. */
    fun coachSettings(text: String?): CoachSettings =
        text?.let { CoachSettingsFile.decode(it.encodeToByteArray()) } ?: CoachSettings()

    fun coachText(settings: CoachSettings): String = CoachSettingsFile.encode(settings).decodeToString()

    // Equipment and cars.

    /** Models the iPhone can record from, in the order Add equipment lists the ones needing no scan. */
    val withoutBluetooth: List<EquipmentModel> = listOf(EquipmentModel.PHONE_GPS, EquipmentModel.PHONE_CAMERA)

    /** The model named [name] (its Kotlin name, "RACEBOX"), or null. */
    fun model(name: String): EquipmentModel? = EquipmentModel.entries.firstOrNull { it.name == name }

    /** Adds an item; one that needs an address and has none changes nothing. */
    fun addEquipment(setup: Setup, model: EquipmentModel, name: String, address: String?, id: String): Setup =
        if (model.needsAddress && address.isNullOrBlank()) {
            setup
        } else {
            setup.addEquipment(model, name, address, newId = { id })
        }

    /** Adds a car; a blank name changes nothing (Kotlin's require would end the app). */
    fun addCar(setup: Setup, name: String, notes: String, id: String): Setup =
        if (name.isBlank()) setup else setup.addCar(name, notes) { id }

    fun updateCar(setup: Setup, id: String, name: String, notes: String): Setup {
        val car = setup.car(id)
        return if (car == null || name.isBlank()) setup else setup.updateCar(car.copy(name = name, notes = notes))
    }

    /** The order Settings › Equipment groups items in, as on Android. */
    val groupOrder: List<EquipmentKind> =
        listOf(EquipmentKind.GNSS, EquipmentKind.CAR_DATA, EquipmentKind.SENSOR, EquipmentKind.CAMERA)

    // The next session, as START uses it.

    /** The phone camera records the next session. */
    fun phoneCameraOn(setup: Setup): Boolean = setup.active().uses(EquipmentModel.PHONE_CAMERA)

    /** The phone camera's video mode ("1080p · 60"), or null when no phone camera is listed. */
    fun phoneVideoLabel(setup: Setup): String? =
        setup.equipment.firstOrNull { it.model == EquipmentModel.PHONE_CAMERA }?.videoMode?.label

    /** Turns the phone camera on or off for the next session. */
    fun withPhoneCamera(setup: Setup, on: Boolean): Setup =
        setup.equipment.firstOrNull { it.model == EquipmentModel.PHONE_CAMERA }
            ?.let { setup.useCamera(it.id, on) } ?: setup

    /** Sets the phone camera's video mode by its [label]; one it does not record changes nothing. */
    fun withPhoneVideo(setup: Setup, label: String): Setup {
        val camera = setup.equipment.firstOrNull { it.model == EquipmentModel.PHONE_CAMERA }
        val mode = camera?.videoModes?.firstOrNull { it.label == label }
        return if (camera != null && mode != null) setup.chooseVideo(camera.id, mode) else setup
    }

    /** How many of the listed cameras record the next session, as Android's home says it. */
    fun camerasLine(setup: Setup): String {
        val all = setup.ofKind(EquipmentKind.CAMERA)
        val on = all.count { it.id in setup.session.cameraIds }
        return when {
            all.isEmpty() -> "No cameras"
            all.size == 1 -> if (on == 1) "${all[0].name} on" else "${all[0].name} off"
            else -> "$on of ${all.size} cameras on"
        }
    }

    /** The coach is on: anything but Off. */
    fun coachOn(s: CoachSettings): Boolean = s.verbosity != Verbosity.Off

    /**
     * The coach settings a session uses: the output delay iOS reports now ([phoneDelayMillis]) replaces
     * the one set, as Android's measured delay does (FBBD-49); the set one is used when iOS gives none.
     */
    fun sessionCoach(s: CoachSettings, phoneDelayMillis: Int): CoachSettings =
        if (phoneDelayMillis > 0) withDelay(s, phoneDelayMillis) else s

    // The spoken coach.

    val verbosities: List<Verbosity> = Verbosity.entries.toList()
    val languages: List<CoachLanguage> = CoachLanguage.entries.toList()
    val voiceModes: List<VoiceMode> = VoiceMode.entries.toList()
    val maxLearningLaps: Int = CoachSettings.MaxLearningLaps
    val maxAudioDelayMillis: Int = CoachSettings.MaxAudioDelayMillis
    val minRate: Float = Speech.MinRate
    val maxRate: Float = Speech.MaxRate
    val minPitch: Float = Speech.MinPitch
    val maxPitch: Float = Speech.MaxPitch

    /** The cue Test voice says, as on Android: "Turn 2, brake at 100, 10 later". */
    fun sampleCue(language: CoachLanguage): String = PhraseBook.of(language).corner(
        Corner(SampleCorner, 0.0, 0.0, 0.0, 0.0, Turn.Straight),
        Advice.BrakeLater(SampleMeters, at = SampleAt),
    )

    private const val SampleCorner = 2
    private const val SampleAt = 100
    private const val SampleMeters = 10

    fun withVerbosity(s: CoachSettings, v: Verbosity): CoachSettings = s.copy(verbosity = v)

    fun withLanguage(s: CoachSettings, l: CoachLanguage): CoachSettings =
        // A voice of the other language would not say these phrases.
        s.copy(language = l, speech = s.speech.copy(voiceName = null))

    fun withVoiceMode(s: CoachSettings, v: VoiceMode): CoachSettings = s.copy(voice = v)

    fun withDelay(s: CoachSettings, millis: Int): CoachSettings =
        s.copy(audioDelayMillis = millis.coerceIn(0, CoachSettings.MaxAudioDelayMillis))

    fun withLearningLaps(s: CoachSettings, laps: Int): CoachSettings =
        s.copy(learningLaps = laps.coerceIn(0, CoachSettings.MaxLearningLaps))

    fun withVoice(s: CoachSettings, voiceName: String?): CoachSettings =
        s.copy(speech = s.speech.copy(voiceName = voiceName))

    fun withRate(s: CoachSettings, rate: Float): CoachSettings =
        s.copy(speech = s.speech.copy(rate = rate.coerceIn(Speech.MinRate, Speech.MaxRate)))

    fun withPitch(s: CoachSettings, pitch: Float): CoachSettings =
        s.copy(speech = s.speech.copy(pitch = pitch.coerceIn(Speech.MinPitch, Speech.MaxPitch)))
}
