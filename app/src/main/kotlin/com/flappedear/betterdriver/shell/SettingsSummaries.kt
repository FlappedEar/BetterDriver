package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.feature.track.TrackBrightness
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.Setup
import java.util.Locale

// The lines of the Settings page (UI-137 B): what each topic has set.

/** What a topic's line on the Settings page says is set (UI-137 B). */
@Suppress("LongParameterList")
internal fun topicSummary(
    topic: SettingsTopic,
    setup: Setup,
    trackCount: Int,
    trackScreen: TrackScreenPrefs,
    coach: CoachSettings,
    version: String,
    freeGb: Double,
): String = when (topic) {
    SettingsTopic.Equipment -> equipmentSummary(setup.equipment)
    SettingsTopic.Cars -> carsSummary(setup)
    SettingsTopic.Tracks -> if (trackCount == 1) "1 track and its lines" else "$trackCount tracks and their lines"
    SettingsTopic.TrackScreen -> trackScreenSummary(trackScreen)
    SettingsTopic.Video -> videoSummary(setup, freeGb)
    SettingsTopic.Coach -> coachLabel(coach)
    SettingsTopic.App -> "$version · permissions · backup"
    SettingsTopic.Diagnostics -> "Share or clear the app's log"
}

private fun carsSummary(setup: Setup): String = setup.car(setup.session.carId)?.let { "${it.name} · next session" }
    ?: if (setup.cars.isEmpty()) "No cars yet" else "${setup.cars.size} cars"

private fun trackScreenSummary(p: TrackScreenPrefs): String {
    val light = if (p.brightness == TrackBrightness.Max) "Full brightness" else "Phone's brightness"
    return "$light · GNSS sound ${if (p.gnssSound) "on" else "off"}"
}

private fun videoSummary(setup: Setup, freeGb: Double): String {
    val free = String.format(Locale.ROOT, "%.0f GB free", freeGb)
    return setup.equipment.firstOrNull { it.videoMode != null }?.let { "${it.name} ${videoLabel(it)} · $free" } ?: free
}

/** "RaceBox Micro 3241, vLinker MC+, Polar H10 and 2 more". */
private fun equipmentSummary(equipment: List<Equipment>): String = when {
    equipment.isEmpty() -> "None added"
    equipment.size <= ShownNames -> equipment.joinToString(", ") { it.name }
    else -> equipment.take(ShownNames).joinToString(", ") { it.name } + " and ${equipment.size - ShownNames} more"
}

private const val ShownNames = 3
