package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.feature.track.TileChoiceGroup
import com.flappedear.betterdriver.feature.track.TrackScreenState
import com.flappedear.betterdriver.setup.TrackPage
import com.flappedear.betterdriver.setup.TrackScreens

// The track screen's layout in Settings › Track screen (FBBD-345): set before driving.

/**
 * The track screen as Settings › Track screen draws it (FBBD-345): the tiles of [screens] with no
 * session running, so a dash for every value, and every tile drawn ([TrackScreenState.editable]) so
 * each can be tapped. The radar and the coach line show as they will in the next session.
 */
internal fun layoutTrackState(screens: TrackScreens, receiverHasG: Boolean, coachOn: Boolean): TrackScreenState {
    val sources = TileSources(null, null, receiverHasG)
    return TrackScreenState(
        status = emptyList(),
        recordingTime = null,
        alert = null,
        deltaSeconds = null,
        lapNumber = null,
        lapMillis = null,
        lastMillis = null,
        bestMillis = null,
        tiles = screens.tiles(TrackPage.MAIN).map { trackTile(it, sources) },
        coachCorner = null,
        coachCue = null,
        secondTiles = screens.tiles(TrackPage.SECOND).map { trackTile(it, sources) },
        editable = true,
        coachOn = coachOn,
        radarOn = receiverHasG,
    )
}

/**
 * A tile's list in Settings › Track screen (FBBD-345): the same channels as on the track screen, with
 * no session running, so no values and none dimmed.
 */
internal fun settingsTileChoices(receiverHasG: Boolean, receiver: String?): List<TileChoiceGroup> =
    tileChoices(TileSources(null, null, receiverHasG), receiver).map { group ->
        group.copy(choices = group.choices.map { it.copy(value = "", hasData = true) })
    }
