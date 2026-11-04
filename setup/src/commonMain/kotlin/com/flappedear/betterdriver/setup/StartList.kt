package com.flappedear.betterdriver.setup

// The list at START (FBBD-446, UI-311 A, UI-312 B, UI-313 A), the same on Android and iOS: every
// item listed in Settings › Equipment, grouped by what it does, each in the same row. Where one item
// records (receiver, car data, heart rate, tyres) the group is one of its items or None; every
// camera can record, so each has a switch. The choice is kept for the next session.

/** The groups of the list at START, in their order. */
enum class StartKind(val title: String, val oneOf: Boolean) {
    GNSS("GPS receiver", true),
    CAMERA("Cameras", false),
    CAR_DATA("Car data", true),
    HEART_RATE("Heart rate", true),
    TPMS("Tyres", true),
}

/** One item of the list: its [name], what it says under the name ([detail]) and whether it is on. */
data class StartRow(val id: String, val name: String, val detail: String, val on: Boolean)

/** A group with its [rows]; in a one-of group [noneOn] says None is chosen. */
data class StartGroup(val kind: StartKind, val rows: List<StartRow>, val noneOn: Boolean) {
    val title: String get() = kind.title
    val oneOf: Boolean get() = kind.oneOf
}

/** The groups that have an item listed, in [StartKind] order. */
fun startGroups(setup: Setup): List<StartGroup> {
    val active = setup.active()
    val used = (listOfNotNull(active.gnss) + active.cameras + active.carData + active.sensors).map { it.id }.toSet()
    return StartKind.entries.mapNotNull { kind ->
        val items = setup.equipment.filter { startKind(it) == kind }
        if (items.isEmpty()) return@mapNotNull null
        // Before a choice every listed adapter is tried: the list shows the first as the one chosen.
        val shownOn = if (kind == StartKind.CAR_DATA && setup.session.carDataId == null) {
            setOfNotNull(items.firstOrNull { it.id in used }?.id)
        } else {
            used
        }
        val rows = items.map { StartRow(it.id, it.name, startDetail(it), it.id in shownOn) }
        StartGroup(kind, rows, noneOn = kind.oneOf && rows.none { it.on })
    }
}

/** The group [e] is in at START. */
fun startKind(e: Equipment): StartKind = when (e.kind) {
    EquipmentKind.GNSS -> StartKind.GNSS
    EquipmentKind.CAMERA -> StartKind.CAMERA
    EquipmentKind.CAR_DATA -> StartKind.CAR_DATA
    EquipmentKind.SENSOR -> if (e.model == EquipmentModel.TPMS_BRIDGE) StartKind.TPMS else StartKind.HEART_RATE
}

/**
 * What a row says under the name: a camera's video mode (changed in Settings › Equipment, UI-312 B)
 * or "Start by hand", else what kind of item it is.
 */
fun startDetail(e: Equipment): String = when {
    e.kind == EquipmentKind.CAMERA && !e.model.controlledByApp -> "Start by hand"
    e.kind == EquipmentKind.CAMERA -> e.videoMode?.label ?: e.model.label
    else -> e.model.label
}

/**
 * Chooses [id] in a one-of group of [kind], or None with null; turns camera [id] on or off with
 * [on]. Ids of another kind are ignored.
 */
fun Setup.chooseAtStart(kind: StartKind, id: String?, on: Boolean = true): Setup = when (kind) {
    StartKind.GNSS -> chooseGnss(id)
    StartKind.CAMERA -> if (id == null) this else useCamera(id, on)
    StartKind.CAR_DATA -> chooseCarData(id ?: NoneChosen)
    StartKind.HEART_RATE -> chooseHeartRate(id ?: NoneChosen)
    StartKind.TPMS -> chooseTpms(id ?: NoneChosen)
}
