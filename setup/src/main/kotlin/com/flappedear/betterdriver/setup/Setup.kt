package com.flappedear.betterdriver.setup

import kotlinx.serialization.Serializable

/** What a piece of equipment does in a session. OBD readers join in Phase 5 (FBBD-3). */
enum class EquipmentKind { GNSS, CAMERA }

/** The models the app can work with. Which of them Arek owns is up to him (FBBD-83). */
@Serializable
enum class EquipmentModel(val kind: EquipmentKind, val label: String) {
    /** RaceBox Micro over BLE (FBBD-43). */
    RACEBOX_MICRO(EquipmentKind.GNSS, "RaceBox Micro"),

    /** Synthetic GNSS for trying the app without hardware. Only there when added on purpose. */
    DEMO_GNSS(EquipmentKind.GNSS, "Demo GNSS"),

    /** The phone's own camera (FBBD-21). There is only one. */
    PHONE_CAMERA(EquipmentKind.CAMERA, "Phone camera"),

    /** Started and stopped with the session through Open GoPro (FBBD-22). */
    GOPRO_HERO11(EquipmentKind.CAMERA, "GoPro HERO11"),

    /** Started by hand; its files are added to the session afterwards (FBBD-22). */
    GOPRO_HERO8(EquipmentKind.CAMERA, "GoPro HERO8"),
    ;

    /** True when the app starts and stops it; a HERO8 is started by hand. */
    val controlledByApp: Boolean get() = this != GOPRO_HERO8

    /** True for models paired over Bluetooth, which need an address. */
    val paired: Boolean get() = this == RACEBOX_MICRO || this == GOPRO_HERO11

    val isGoPro: Boolean get() = this == GOPRO_HERO11 || this == GOPRO_HERO8
}

/** One item in Arek's equipment list. [address] is the Bluetooth address of a paired model. */
@Serializable
data class Equipment(
    val id: String,
    val model: EquipmentModel,
    val name: String,
    val address: String? = null,
) {
    val kind: EquipmentKind get() = model.kind
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
    LAP("Lap", ""),

    /** Combined acceleration; needs a receiver with an accelerometer. */
    G("G", "g"),
}

/** The track screen has room for three tiles (UI-10 option A). */
const val MaxTiles = 3

/** What the next session uses. Ids refer to [Setup.equipment] and [Setup.cars]. */
@Serializable
data class SessionChoice(
    val carId: String? = null,
    val gnssId: String? = null,
    val cameraIds: List<String> = emptyList(),
    val tiles: List<TrackChannel> = listOf(TrackChannel.SPEED, TrackChannel.LAP),
)

/** Why a session cannot start with the current choice. */
enum class StartProblem { NO_GNSS }

/** The next session's choice with ids resolved. Only what is listed here is shown or started. */
data class ActiveSession(
    val car: CarProfile?,
    val gnss: Equipment?,
    val cameras: List<Equipment>,
    val tiles: List<TrackChannel>,
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
@Serializable
data class Setup(
    val equipment: List<Equipment> = emptyList(),
    val cars: List<CarProfile> = emptyList(),
    val session: SessionChoice = SessionChoice(),
) {
    fun equipment(id: String?): Equipment? = equipment.firstOrNull { it.id == id }

    fun car(id: String?): CarProfile? = cars.firstOrNull { it.id == id }

    fun ofKind(kind: EquipmentKind): List<Equipment> = equipment.filter { it.kind == kind }

    val hasGoPro: Boolean get() = equipment.any { it.model.isGoPro }

    fun active(): ActiveSession {
        val gnss = equipment(session.gnssId)?.takeIf { it.kind == EquipmentKind.GNSS }
        return ActiveSession(
            car = car(session.carId),
            gnss = gnss,
            cameras = session.cameraIds.mapNotNull { id -> equipment(id)?.takeIf { it.kind == EquipmentKind.CAMERA } },
            tiles = session.tiles.filter { it in channelsFor(gnss) },
        )
    }

    /**
     * Adds an item. The first GNSS receiver becomes the session's receiver, and a new camera is
     * used in the next session; Arek can turn either off. A model paired over Bluetooth that is
     * already listed at the same address is updated instead of listed twice, and the phone camera
     * is listed once at most.
     */
    fun addEquipment(model: EquipmentModel, name: String = model.label, address: String? = null, newId: () -> String): Setup {
        require(!model.paired || !address.isNullOrBlank()) { "${model.label} needs a Bluetooth address" }
        val same = equipment.firstOrNull {
            it.model == model && (model == EquipmentModel.PHONE_CAMERA || (address != null && it.address == address))
        }
        if (same != null) return replace(same.copy(name = name.ifBlank { model.label }, address = address ?: same.address))
        val item = Equipment(newId(), model, name.ifBlank { model.label }, address)
        val next = copy(equipment = equipment + item)
        return when (model.kind) {
            EquipmentKind.GNSS -> if (active().gnss == null) next.chooseGnss(item.id) else next
            EquipmentKind.CAMERA -> next.useCamera(item.id, true)
        }
    }

    fun renameEquipment(id: String, name: String): Setup {
        val item = equipment(id) ?: return this
        return replace(item.copy(name = name.ifBlank { item.model.label }))
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
        return copy(cars = cars.map { if (it.id == car.id) car.copy(name = car.name.trim(), notes = car.notes.trim()) else it })
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
 * Channels a session can show with [gnss] as its receiver. Lap needs only a position; G needs the
 * RaceBox accelerometer. Without a receiver there is nothing to show.
 */
fun channelsFor(gnss: Equipment?): Set<TrackChannel> = when (gnss?.model) {
    null -> emptySet()
    EquipmentModel.RACEBOX_MICRO -> setOf(TrackChannel.SPEED, TrackChannel.LAP, TrackChannel.G)
    else -> setOf(TrackChannel.SPEED, TrackChannel.LAP)
}
