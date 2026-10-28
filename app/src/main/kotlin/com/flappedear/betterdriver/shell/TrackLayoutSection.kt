package com.flappedear.betterdriver.shell

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.flappedear.betterdriver.feature.track.RadarTiles
import com.flappedear.betterdriver.feature.track.TileChoiceGroup
import com.flappedear.betterdriver.feature.track.TileListDialog
import com.flappedear.betterdriver.feature.track.TrackLayoutPreview
import com.flappedear.betterdriver.feature.track.TrackScreenState
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recording.layoutTrackState
import com.flappedear.betterdriver.recording.settingsTileChoices
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.TrackChannel
import com.flappedear.betterdriver.setup.TrackPage
import com.flappedear.betterdriver.setup.channelsFor

/**
 * The track screen's layout in Settings › Track screen (FBBD-345, UI-304 A), so the tiles are set
 * before driving: both screens drawn as they look, and a tap on a tile opens its list, the same one
 * the track screen had (UI-124 A); the track screen itself has no gesture for it (UI-305 B). Each car
 * has its own layout (UI-306, Arek: "as list of cars, we may have multiple"): [cars] are listed and
 * [carId]'s is drawn, [nextCarId] being the next session's. [state] shows that car's tiles;
 * [onChoose] gets the page (0 main, 1 second), the slot and the channel id, null for an empty tile.
 */
@Suppress("LongParameterList")
@Composable
internal fun TrackLayoutSection(
    state: TrackScreenState,
    choices: () -> List<TileChoiceGroup>,
    onChoose: (page: Int, slot: Int, id: String?) -> Unit,
    onReset: () -> Unit,
    cars: List<CarProfile> = emptyList(),
    carId: String? = null,
    nextCarId: String? = null,
    onPickCar: (String) -> Unit = {},
) {
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    if (cars.isNotEmpty()) {
        SectionLabel("Layout for")
        ShellCard {
            cars.forEachIndexed { i, car ->
                ListRow(
                    car.name,
                    divider = i > 0,
                    supporting = if (car.id == nextCarId) "Next session" else null,
                    onClick = { onPickCar(car.id) },
                ) {
                    RadioButton(selected = car.id == carId, onClick = null)
                }
            }
        }
    }
    SectionLabel("Screen 1 · tap a tile to change it")
    TrackLayoutPreview(state, 0, { editing = 0 to it }, Modifier.testTag(TrackLayoutTags.Main))
    SectionLabel("Screen 2 · swipe left while driving")
    TrackLayoutPreview(state, 1, { editing = 1 to it }, Modifier.testTag(TrackLayoutTags.Second))
    DimText(
        if (state.radarOn) {
            "Screen 2 shows the G radar and the first $RadarTiles tiles. A tile with no data in a session is left out."
        } else {
            "Screen 2 shows the G radar when the receiver gives G. A tile with no data in a session is left out."
        },
    )
    ShellCard { ListRow("Reset tiles to default", divider = false, onClick = onReset) }
    editing?.let { (page, slot) ->
        val tiles = if (page == 0) state.tiles else state.secondTiles
        TileListDialog(
            title = "Tile ${slot + 1} · screen ${page + 1}",
            current = tiles.getOrNull(slot)?.id,
            groups = choices(),
            onChoose = { id ->
                onChoose(page, slot, id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * Settings › Track screen's layout (FBBD-345), set before driving: a car's tiles, the next session's
 * car first.
 */
@Composable
internal fun SettingsTrackLayout(repo: SetupRepo, coachOn: Boolean) {
    val setup = repo.setup
    var picked by rememberSaveable { mutableStateOf(setup.session.carId) }
    val carId = picked?.takeIf { setup.car(it) != null } ?: setup.session.carId
    val gnss = setup.active().gnss
    val hasG = TrackChannel.G in channelsFor(gnss)
    TrackLayoutSection(
        layoutTrackState(setup.trackScreensFor(carId), hasG, coachOn),
        choices = { settingsTileChoices(hasG, gnss?.name) },
        onChoose = { page, slot, id ->
            repo.chooseTile(if (page == 0) TrackPage.MAIN else TrackPage.SECOND, slot, id, carId)
            DebugLog.i("Settings", "tile car=$carId page=$page slot=$slot id=${id ?: "empty"}")
        },
        onReset = {
            repo.resetTiles(carId)
            DebugLog.i("Settings", "tiles of car=$carId reset to default")
        },
        cars = setup.cars,
        carId = carId,
        nextCarId = setup.session.carId,
        onPickCar = { picked = it },
    )
}

/** Test tags of the layout's two screens. */
internal object TrackLayoutTags {
    const val Main = "layout-main"
    const val Second = "layout-second"
}
