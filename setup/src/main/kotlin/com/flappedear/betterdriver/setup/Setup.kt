package com.flappedear.betterdriver.setup

import kotlinx.serialization.Serializable

/**
 * What a piece of equipment does in a session. A sensor (a heart-rate monitor, FBBD-119) records
 * with every session while it is listed. OBD readers join in Phase 5 (FBBD-3).
 */
enum class EquipmentKind { GNSS, CAMERA, SENSOR }

/**
 * The kinds of equipment the app can work with (FBBD-105). Each is a family, not one product:
 * which RaceBox, Bluetooth GPS or GoPro it is comes from the device itself or from Arek, so a new
 * camera or receiver of a known family needs no new entry here.
 */
@Serializable
enum class EquipmentModel(val kind: EquipmentKind, val label: String) {
    /**
     * A RaceBox Mini, Mini S or Micro over BLE (FBBD-43). All three speak the same protocol
     * (RaceBox BLE protocol rev. 8); the advertised name tells them apart ([Equipment.advertisedName]).
     */
    RACEBOX(EquipmentKind.GNSS, "RaceBox"),

    /**
     * A Bluetooth GPS receiver that sends NMEA 0183 over the serial port profile, such as a Qstarz
     * or a Garmin GLO (FBBD-108). Paired in Android's Bluetooth settings first.
     */
    NMEA_GNSS(EquipmentKind.GNSS, "Bluetooth GPS"),

    /**
     * A Bluetooth LE GPS receiver with the standard Location and Navigation Service (0x1819), as
     * RaceChrono supports (FBBD-114). Found by a scan.
     */
    LNS_GNSS(EquipmentKind.GNSS, "Bluetooth LE GPS"),

    /**
     * A RaceChrono DIY Bluetooth LE GPS device (service 0x1FF8,
     * https://github.com/aollin/racechrono-ble-diy-device), as RaceChrono supports (FBBD-118). Found by a scan.
     */
    DIY_GNSS(EquipmentKind.GNSS, "RaceChrono DIY GPS"),

    /**
     * A receiver on Wi-Fi that sends NMEA 0183, or a RaceChrono DIY device sending $RC2/$RC3 lines,
     * over TCP (FBBD-115, https://racechrono.com/article/2572). Its address is "host:port"
     * ([TcpAddress]), typed in when it is added.
     */
    TCP_GNSS(EquipmentKind.GNSS, "Wi-Fi GPS"),

    /**
     * A RACELOGIC VBOX Sport (FBBD-116): the RACELOGIC Serial Stream at 10 Hz ("Serial Data Format",
     * https://vboxmotorsport.co.uk/downloads/VBOX-Sport_Serial_Format.pdf). Bluetooth Classic, paired
     * in Android's Bluetooth settings like [NMEA_GNSS]; it shows there as "VBSport" and its serial number.
     */
    VBOX_SPORT(EquipmentKind.GNSS, "VBOX Sport"),

    /**
     * A Dragy Pro / DRG70 over BLE (FBBD-117), found by a scan. Dragy publishes no protocol: this
     * follows the community-documented one (https://github.com/jremick/dragy-dash), unverified on
     * hardware.
     */
    DRAGY(EquipmentKind.GNSS, "Dragy"),

    /** Synthetic GNSS for trying the app without hardware. Only there when added on purpose. */
    DEMO_GNSS(EquipmentKind.GNSS, "Demo GNSS"),

    /**
     * Synthetic GNSS of a car lapping a made-up circuit whose track is saved for it (FBBD-233): laps,
     * sectors and the coach's cues without a track day. Only there when added on purpose.
     */
    DEMO_TRACK(EquipmentKind.GNSS, "Demo track"),

    /** A Bluetooth LE heart-rate monitor (standard Heart Rate service, FBBD-119). Found by a scan. */
    HEART_RATE(EquipmentKind.SENSOR, "Heart-rate monitor"),

    /** The phone's own camera (FBBD-21). There is only one. */
    PHONE_CAMERA(EquipmentKind.CAMERA, "Phone camera"),

    /**
     * An Open GoPro camera (HERO9 Black and later, MAX2), started and stopped with the session
     * (FBBD-22). Which camera it is, and so which video modes it offers, is [Equipment.goPro].
     */
    GOPRO(EquipmentKind.CAMERA, "GoPro"),

    /**
     * A GoPro started by hand whose files are added to the session afterwards: cameras without
     * Open GoPro (HERO8 and earlier, the first MAX), or any GoPro Arek prefers to run that way.
     */
    GOPRO_FILES(EquipmentKind.CAMERA, "GoPro, files added after the session"),

    /**
     * Any other action camera started by hand (DJI Action, Insta360, Garmin, ...), whose MP4 files
     * are added after the session and lined up by the camera's clock (FBBD-120). Their GPS formats
     * are not documented, so no GPS alignment.
     */
    CAMERA_FILES(EquipmentKind.CAMERA, "Other action camera"),
    ;

    /** True when the app starts and stops it; a camera whose files are added is started by hand. */
    val controlledByApp: Boolean get() = !filesAdded

    /** True for a camera started by hand whose files are added after the session. */
    val filesAdded: Boolean get() = this == GOPRO_FILES || this == CAMERA_FILES

    /** True for a camera other than the phone's: its video shows per camera on the session card. */
    val actionCamera: Boolean get() = isGoPro || this == CAMERA_FILES

    /** True for models paired over Bluetooth, which need an address. */
    val paired: Boolean
        get() = this in setOf(RACEBOX, NMEA_GNSS, LNS_GNSS, DIY_GNSS, VBOX_SPORT, DRAGY, GOPRO, HEART_RATE)

    /** True for models reached at an address: a Bluetooth one, or a Wi-Fi receiver's "host:port". */
    val needsAddress: Boolean get() = paired || this == TCP_GNSS

    val isGoPro: Boolean get() = this == GOPRO || this == GOPRO_FILES

    /** Listed once at most: there is one phone camera. Any number of GoPros and receivers can be listed. */
    val single: Boolean get() = this == PHONE_CAMERA

    /**
     * The phone camera's video modes (FBBD-88), best first, narrowed at run time to what the phone
     * reports. A GoPro's modes depend on the camera: see [Equipment.videoModes].
     */
    val videoModes: List<VideoMode> get() = if (this == PHONE_CAMERA) PhoneVideoModes else emptyList()

    /** The phone camera's mode until Arek chooses one. */
    val defaultVideo: VideoMode? get() = videoModes.defaultMode()
}

/**
 * GoPro cameras by generation (FBBD-105). Open GoPro cameras and their model ids, frame sizes and
 * frame rates are from the Open GoPro spec (github.com/gopro/OpenGoPro, gh-pages branch,
 * references.json "cameras" and http/openapi.json Setting_2 and Setting_3 option metadata):
 * every Open GoPro camera takes 4K (1) and 1080p (9) at 60 (5) and 30 (8) fps; HERO9 to HERO12
 * also take 2.7K (4); only the HERO13 takes 720p (12). Cameras before the HERO9 have no Open GoPro,
 * so their files are added after the session ([EquipmentModel.GOPRO_FILES]).
 */
@Suppress("MagicNumber") // Model numbers from the Open GoPro spec.
@Serializable
enum class GoProModel(
    val label: String,
    /** The model number the camera reports in Get Hardware Info; null for cameras without Open GoPro. */
    val modelId: Int?,
    private val sizes: List<VideoSize>,
) {
    HERO13_BLACK("HERO13 Black", 65, listOf(VideoSize.UHD, VideoSize.QHD, VideoSize.FHD, VideoSize.HD)),
    MAX_2("MAX2", 64, listOf(VideoSize.UHD, VideoSize.FHD)),
    HERO12_BLACK("HERO12 Black", 62, listOf(VideoSize.UHD, VideoSize.QHD, VideoSize.FHD)),
    HERO11_BLACK_MINI("HERO11 Black Mini", 60, listOf(VideoSize.UHD, VideoSize.QHD, VideoSize.FHD)),
    HERO11_BLACK("HERO11 Black", 58, listOf(VideoSize.UHD, VideoSize.QHD, VideoSize.FHD)),
    HERO10_BLACK("HERO10 Black", 57, listOf(VideoSize.UHD, VideoSize.QHD, VideoSize.FHD)),
    HERO9_BLACK("HERO9 Black", 55, listOf(VideoSize.UHD, VideoSize.QHD, VideoSize.FHD)),

    /** An Open GoPro camera this build does not know yet, such as a newer model: the modes all of them take. */
    OTHER_OPEN_GOPRO("GoPro", null, listOf(VideoSize.UHD, VideoSize.FHD)),

    HERO8_BLACK("HERO8 Black", null, emptyList()),
    MAX("MAX", null, emptyList()),
    HERO7_BLACK("HERO7 Black", null, emptyList()),
    HERO6_BLACK("HERO6 Black", null, emptyList()),
    HERO5_BLACK("HERO5 Black", null, emptyList()),

    /** Any other GoPro whose files are added after the session. */
    OTHER("Other GoPro", null, emptyList()),
    ;

    /** True for cameras the app can start, stop and set over Open GoPro. */
    val openGoPro: Boolean get() = modelId != null || this == OTHER_OPEN_GOPRO

    /** The video modes the app can set on this camera, best first; empty for cameras without Open GoPro. */
    val videoModes: List<VideoMode> get() = sizes.flatMap { listOf(VideoMode(it, HighFps), VideoMode(it, LowFps)) }

    companion object {
        /** Open GoPro cameras, newest first, for choosing a camera that was paired before its model was known. */
        val OpenGoPro: List<GoProModel> = entries.filter { it.openGoPro }

        /** Cameras whose files are added after the session, newest first. */
        val FileImport: List<GoProModel> = entries.filterNot { it.openGoPro }

        /**
         * The camera for the model number from Get Hardware Info. A number this build does not
         * know is an Open GoPro camera too, such as a model newer than this build.
         */
        fun ofModelId(id: Int?): GoProModel =
            entries.firstOrNull { it.modelId != null && it.modelId == id } ?: OTHER_OPEN_GOPRO

        /** The camera for the model name from Get Hardware Info ("HERO12 Black"), or null. */
        fun ofName(name: String?): GoProModel? = entries.firstOrNull {
            it.modelId != null && name != null && name.trim().equals(it.label, ignoreCase = true)
        }
    }
}

/** Video frame sizes, largest first. */
@Serializable
enum class VideoSize(val label: String) { UHD("4K"), QHD("2.7K"), FHD("1080p"), HD("720p") }

/** A camera's video setting: frame size and frames per second (FBBD-88). */
@Serializable
data class VideoMode(val size: VideoSize, val fps: Int) {
    val label: String get() = "${size.label} · $fps"
}

private const val HighFps = 60
private const val LowFps = 30

private val PhoneVideoModes = listOf(VideoSize.UHD, VideoSize.FHD, VideoSize.HD)
    .flatMap { listOf(VideoMode(it, HighFps), VideoMode(it, LowFps)) }

/** The mode used until Arek chooses one: 1080p at 60, lighter on heat, storage and download than 4K. */
private fun List<VideoMode>.defaultMode(): VideoMode? = VideoMode(VideoSize.FHD, HighFps).takeIf { it in this }


/**
 * One item in Arek's equipment list. [address] is the Bluetooth address of a paired model.
 * [video] is the chosen video mode of a camera the app sets; null means the camera's default.
 */
@Serializable
data class Equipment(
    val id: String,
    val model: EquipmentModel,
    val name: String,
    val address: String? = null,
    /**
     * The name the device advertised when it was paired. It tells a RaceBox Micro from a Mini;
     * [name] is Arek's and can be anything.
     */
    val advertisedName: String? = null,
    val video: VideoMode? = null,
    /** Which GoPro this is, for [EquipmentModel.GOPRO] and [EquipmentModel.GOPRO_FILES]; null for other models. */
    val goPro: GoProModel? = null,
) {
    val kind: EquipmentKind get() = model.kind

    /** The video modes the app can set on this camera, best first; empty when the app does not set it. */
    val videoModes: List<VideoMode> get() = when (model) {
        EquipmentModel.GOPRO -> (goPro ?: GoProModel.OTHER_OPEN_GOPRO).videoModes
        else -> model.videoModes
    }

    /** The mode used until Arek chooses one. */
    val defaultVideo: VideoMode? get() = videoModes.defaultMode()

    /** The video mode this camera records with, or null when the app does not set it. */
    val videoMode: VideoMode? get() = video?.takeIf { it in videoModes } ?: defaultVideo

    /** What it is, for the equipment screen: "GoPro HERO12 Black", "RaceBox Mini S", "Bluetooth GPS". */
    val modelLabel: String get() = when (model) {
        EquipmentModel.GOPRO, EquipmentModel.GOPRO_FILES -> when (goPro) {
            null, GoProModel.OTHER_OPEN_GOPRO, GoProModel.OTHER -> model.label
            else -> "GoPro ${goPro.label}"
        }
        EquipmentModel.RACEBOX -> raceBoxModel(advertisedName) ?: model.label
        else -> model.label
    }
}

/** Which GoPro a new [model] item is: an Open GoPro camera of unknown model is listed as one. */
private fun goProFor(model: EquipmentModel, goPro: GoProModel?): GoProModel? = when (model) {
    EquipmentModel.GOPRO -> goPro?.takeIf { it.openGoPro } ?: GoProModel.OTHER_OPEN_GOPRO
    EquipmentModel.GOPRO_FILES -> goPro ?: GoProModel.OTHER
    else -> null
}

/**
 * The RaceBox model from its advertised name ("RaceBox Mini S 1234" is a Mini S), or null for a
 * name that is not a RaceBox's. The RaceBox BLE protocol (rev. 8) names the devices "RaceBox Mini
 * ", "RaceBox Mini S " and "RaceBox Micro " followed by the serial number.
 */
fun raceBoxModel(advertisedName: String?): String? = when {
    advertisedName == null -> null
    advertisedName.startsWith("RaceBox Micro") -> "RaceBox Micro"
    advertisedName.startsWith("RaceBox Mini S") -> "RaceBox Mini S"
    advertisedName.startsWith("RaceBox Mini") -> "RaceBox Mini"
    else -> null
}

/** One of Arek's cars. The PID set for car data attaches here in Phase 5 (FBBD-16). */
@Serializable
data class CarProfile(
    val id: String,
    val name: String,
    val notes: String = "",
)

/** Values a track-screen tile can show, and what the session needs for each. */
@Serializable
enum class TrackChannel(val label: String, val unit: String) {
    SPEED("Speed", "km/h"),

    /**
     * No longer offered: the lap number is always on the lap times block (UI-82). Kept so a saved
     * choice that has it still reads; [Setup.active] leaves it out.
     */
    LAP("Lap", ""),

    /** Combined acceleration; needs a receiver with an accelerometer. */
    G("G", "g"),
}

/** The track screen has room for three tiles (UI-10 option A). */
const val MaxTiles = 3

/** The tiles Settings offers, in its order: every channel but [TrackChannel.LAP] (UI-82). */
val OfferedTiles: List<TrackChannel> = TrackChannel.entries - TrackChannel.LAP

/** What the next session uses. Ids refer to [Setup.equipment] and [Setup.cars]. */
@Serializable
data class SessionChoice(
    val carId: String? = null,
    val gnssId: String? = null,
    val cameraIds: List<String> = emptyList(),
    val tiles: List<TrackChannel> = listOf(TrackChannel.SPEED),
)

/** Why a session cannot start with the current choice. */
enum class StartProblem { NO_GNSS }

/** The next session's choice with ids resolved. Only what is listed here is shown or started. */
data class ActiveSession(
    val car: CarProfile?,
    val gnss: Equipment?,
    val cameras: List<Equipment>,
    val tiles: List<TrackChannel>,
    /** Sensors recorded with the session: every listed one (FBBD-119). */
    val sensors: List<Equipment> = emptyList(),
) {
    val startProblem: StartProblem? get() = if (gnss == null) StartProblem.NO_GNSS else null

    /** Cameras the app starts and stops with the session. */
    val appCameras: List<Equipment> get() = cameras.filter { it.model.controlledByApp }

    /** Cameras Arek starts by hand, so the app can remind him. */
    val manualCameras: List<Equipment> get() = cameras.filterNot { it.model.controlledByApp }

    fun uses(model: EquipmentModel): Boolean = gnss?.model == model || cameras.any { it.model == model }
}

/**
 * Everything Arek has set up: his equipment, his cars and the choice for the next session
 * (FBBD-83). Immutable; every change returns a new [Setup] that the caller saves. Removed items
 * drop out of the session choice. Tiles are kept as Arek chose them and [active] leaves out the
 * ones the chosen receiver cannot fill, so switching receivers back and forth loses nothing.
 */
@Suppress("TooManyFunctions") // One small operation per thing Arek can change.
@Serializable
data class Setup(
    val equipment: List<Equipment> = emptyList(),
    val cars: List<CarProfile> = emptyList(),
    val session: SessionChoice = SessionChoice(),
) {
    fun equipment(id: String?): Equipment? = equipment.firstOrNull { it.id == id }

    fun car(id: String?): CarProfile? = cars.firstOrNull { it.id == id }

    fun ofKind(kind: EquipmentKind): List<Equipment> = equipment.filter { it.kind == kind }

    val hasGoPro: Boolean get() = equipment.any { it.model.actionCamera }

    fun active(): ActiveSession {
        val gnss = equipment(session.gnssId)?.takeIf { it.kind == EquipmentKind.GNSS }
        return ActiveSession(
            car = car(session.carId),
            gnss = gnss,
            cameras = session.cameraIds.mapNotNull { id -> equipment(id)?.takeIf { it.kind == EquipmentKind.CAMERA } },
            tiles = session.tiles.filter { it in channelsFor(gnss) },
            sensors = ofKind(EquipmentKind.SENSOR),
        )
    }

    /**
     * Adds an item. The first GNSS receiver becomes the session's receiver, and a new camera is
     * used in the next session; Arek can turn either off. A model paired over Bluetooth that is
     * already listed at the same address is updated instead of listed twice. Only the phone camera
     * is listed once at most; any number of GoPros and receivers can be listed. [goPro] says which
     * GoPro a GoPro is; an Open GoPro camera whose model is not known is listed as one.
     */
    @Suppress("LongParameterList") // Each is a property of the new item.
    fun addEquipment(
        model: EquipmentModel,
        name: String = model.label,
        address: String? = null,
        newId: () -> String,
        goPro: GoProModel? = null,
    ): Setup {
        require(!model.needsAddress || !address.isNullOrBlank()) { "${model.label} needs an address" }
        val camera = goProFor(model, goPro)
        val same = listedAs(model, address)
        val advertised = name.takeIf { address != null && it.isNotBlank() }
        if (same != null) {
            return replace(
                same.copy(
                    name = name.ifBlank { model.label },
                    address = address ?: same.address,
                    advertisedName = advertised ?: same.advertisedName,
                    goPro = goPro?.let { camera } ?: same.goPro,
                ),
            )
        }
        val item = Equipment(newId(), model, name.ifBlank { model.label }, address, advertised, goPro = camera)
        val next = copy(equipment = equipment + item)
        return when (model.kind) {
            EquipmentKind.GNSS -> if (active().gnss == null) next.chooseGnss(item.id) else next
            EquipmentKind.CAMERA -> next.useCamera(item.id, true)
            EquipmentKind.SENSOR -> next
        }
    }

    /** The item a new [model] at [address] would be: the one listed already, if any. */
    private fun listedAs(model: EquipmentModel, address: String?): Equipment? = equipment.firstOrNull {
        it.model == model && (model.single || (address != null && it.address == address))
    }

    /**
     * Says which GoPro item [id] is. A camera driven over Open GoPro takes only Open GoPro models;
     * a chosen video mode the new model lacks falls back to its default ([Equipment.videoMode]).
     */
    fun chooseGoPro(id: String, goPro: GoProModel): Setup {
        val item = equipment(id)?.takeIf {
            it.model == EquipmentModel.GOPRO_FILES || (it.model == EquipmentModel.GOPRO && goPro.openGoPro)
        }
        return if (item == null) this else replace(item.copy(goPro = goPro))
    }

    fun renameEquipment(id: String, name: String): Setup {
        val item = equipment(id) ?: return this
        return replace(item.copy(name = name.ifBlank { item.model.label }))
    }

    /** Sets a camera's video mode; ignored for items and modes the model does not offer. */
    fun chooseVideo(id: String, mode: VideoMode): Setup {
        val item = equipment(id)?.takeIf { mode in it.videoModes } ?: return this
        return replace(item.copy(video = mode))
    }

    /** Removes an item and drops it from the session choice. Another receiver takes over if there is one. */
    fun removeEquipment(id: String): Setup {
        val item = equipment(id) ?: return this
        val left = equipment - item
        val gnssId = when {
            session.gnssId != id -> session.gnssId
            else -> left.firstOrNull { it.kind == EquipmentKind.GNSS }?.id
        }
        return copy(
            equipment = left,
            session = session.copy(gnssId = gnssId, cameraIds = session.cameraIds - id),
        )
    }

    /** Adds a car. The first car is chosen for the next session. */
    fun addCar(name: String, notes: String = "", newId: () -> String): Setup {
        require(name.isNotBlank()) { "a car needs a name" }
        val car = CarProfile(newId(), name.trim(), notes.trim())
        val next = copy(cars = cars + car)
        return if (session.carId == null) next.chooseCar(car.id) else next
    }

    fun updateCar(car: CarProfile): Setup {
        require(car.name.isNotBlank()) { "a car needs a name" }
        if (car(car.id) == null) return this
        val trimmed = car.copy(name = car.name.trim(), notes = car.notes.trim())
        return copy(cars = cars.map { if (it.id == car.id) trimmed else it })
    }

    fun removeCar(id: String): Setup = copy(
        cars = cars.filterNot { it.id == id },
        session = if (session.carId == id) session.copy(carId = null) else session,
    )

    /** Chooses the car for the next session; null for none. */
    fun chooseCar(id: String?): Setup =
        if (id != null && car(id) == null) this else copy(session = session.copy(carId = id))

    /** Chooses the GNSS receiver for the next session; null for none. */
    fun chooseGnss(id: String?): Setup {
        if (id != null && equipment(id)?.kind != EquipmentKind.GNSS) return this
        return copy(session = session.copy(gnssId = id))
    }

    /** Turns a camera on or off for the next session. */
    fun useCamera(id: String, on: Boolean): Setup {
        if (equipment(id)?.kind != EquipmentKind.CAMERA) return this
        val ids = if (on) (session.cameraIds - id) + id else session.cameraIds - id
        return copy(session = session.copy(cameraIds = ids))
    }

    /** Sets the track-screen tiles, in order; repeats are dropped and at most [MaxTiles] kept. */
    fun chooseTiles(tiles: List<TrackChannel>): Setup =
        copy(session = session.copy(tiles = tiles.distinct().take(MaxTiles)))

    private fun replace(item: Equipment): Setup = copy(equipment = equipment.map { if (it.id == item.id) item else it })

    companion object {
        /** A fresh install: only the phone camera, which is part of the phone, and nothing else. */
        fun fresh(newId: () -> String): Setup = Setup().addEquipment(EquipmentModel.PHONE_CAMERA, newId = newId)
    }
}

/**
 * Channels a session can show with [gnss] as its receiver. Speed needs only a position; G needs an
 * accelerometer, which every RaceBox has (Mini, Mini S and Micro send acceleration in each data
 * message, RaceBox BLE protocol rev. 8). A plain NMEA receiver does not report one, but a RaceChrono
 * DIY device streaming $RC2/$RC3 lines over Bluetooth or Wi-Fi does (FBBD-115), and the setup
 * cannot tell them apart, so G is offered for both. Without a receiver there is nothing to show.
 */
fun channelsFor(gnss: Equipment?): Set<TrackChannel> = when (gnss?.model) {
    null -> emptySet()
    EquipmentModel.RACEBOX, EquipmentModel.NMEA_GNSS, EquipmentModel.TCP_GNSS ->
        setOf(TrackChannel.SPEED, TrackChannel.G)
    else -> setOf(TrackChannel.SPEED)
}
