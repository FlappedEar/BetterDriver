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
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupFormatException
import com.flappedear.betterdriver.setup.SetupJson
import com.flappedear.betterdriver.setup.WifiObdAddress

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

    /**
     * What Add equipment offers without a scan, in Android's order: the phone camera, the phone's GPS
     * and the Wi-Fi OBD-II adapter once, cameras run by hand (their files added after the session,
     * FBBD-343) any number of times.
     */
    fun withoutBluetooth(setup: Setup): List<EquipmentModel> = buildList {
        if (setup.equipment.none { it.model == EquipmentModel.PHONE_CAMERA }) add(EquipmentModel.PHONE_CAMERA)
        add(EquipmentModel.GOPRO_FILES)
        add(EquipmentModel.CAMERA_FILES)
        if (setup.equipment.none { it.model == EquipmentModel.PHONE_GPS }) add(EquipmentModel.PHONE_GPS)
        if (setup.equipment.none { it.model == EquipmentModel.OBD_WIFI }) add(EquipmentModel.OBD_WIFI)
    }

    /**
     * Before FBBD-349 the iPhone tried a Wi-Fi OBD-II adapter at [WifiObdAddress] in every session
     * without it being listed. Now only listed adapters are used, as on Android, so a setup saved
     * before gets that adapter listed once and keeps reading the car as before (Android's version 3
     * upgrade did the same); one already listed is kept as it is. Arek can remove it.
     */
    fun withEarlierWifiObd(setup: Setup, id: String): Setup =
        if (setup.equipment.any { it.model == EquipmentModel.OBD_WIFI }) {
            setup
        } else {
            setup.addEquipment(EquipmentModel.OBD_WIFI, EquipmentModel.OBD_WIFI.label, WifiObdAddress, newId = { id })
        }

    /**
     * What Add equipment offers under "Try the app" (UI-79 B), as on Android: the Demo track once
     * (FBBD-373). Android's Demo GNSS is not on the iPhone.
     */
    fun tryTheApp(setup: Setup): List<EquipmentModel> = listOf(EquipmentModel.DEMO_TRACK)
        .filter { m -> setup.equipment.none { it.model == m } }

    /** The model named [name] (its Kotlin name, "RACEBOX"), or null. */
    fun model(name: String): EquipmentModel? = EquipmentModel.entries.firstOrNull { it.name == name }

    /**
     * Adds an item; one that needs an address and has none changes nothing. The Wi-Fi OBD-II adapter
     * without one is at [WifiObdAddress], as Android adds it.
     */
    fun addEquipment(setup: Setup, model: EquipmentModel, name: String, address: String?, id: String): Setup {
        val at = if (model == EquipmentModel.OBD_WIFI && address.isNullOrBlank()) WifiObdAddress else address
        return if (model.needsAddress && at.isNullOrBlank()) {
            setup
        } else {
            setup.addEquipment(model, name, at, newId = { id })
        }
    }

    /** Adds a GoPro started by hand, named after its generation as on Android ("GoPro HERO8 Black"). */
    fun addGoProFiles(setup: Setup, goPro: GoProModel, id: String): Setup {
        val name = when (goPro) {
            GoProModel.OTHER, GoProModel.OTHER_OPEN_GOPRO -> "GoPro"
            else -> "GoPro ${goPro.label}"
        }
        return setup.addEquipment(EquipmentModel.GOPRO_FILES, name, null, newId = { id }, goPro = goPro)
    }

    /**
     * Adds an Open GoPro camera paired over Bluetooth at [address] (the iPhone's identifier for it),
     * as Android's pairing does: [goPro] is the model its hardware info named, and it is used in
     * the next session.
     */
    fun addOpenGoPro(setup: Setup, name: String, address: String, goPro: GoProModel, id: String): Setup =
        setup.addEquipment(EquipmentModel.GOPRO, name, address, newId = { id }, goPro = goPro)

    /** Turns camera [id] on or off for the next session, as Android's home toggles (UI-36). */
    fun useCamera(setup: Setup, id: String, on: Boolean): Setup = setup.useCamera(id, on)

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

    // A session's coach.

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
