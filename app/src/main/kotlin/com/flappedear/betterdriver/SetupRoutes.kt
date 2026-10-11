package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flappedear.betterdriver.core.ble.scanRaceBoxes
import com.flappedear.betterdriver.gopro.GoPros
import com.flappedear.betterdriver.gopro.scanGoPros
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.shell.AddEquipmentScreen
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.CarScreen
import com.flappedear.betterdriver.shell.EquipmentScreen
import com.flappedear.betterdriver.shell.NearbyDevice
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.shell.newSetupId
import java.io.IOException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** Equipment and car screens (UI-33 to UI-35), reached from Settings, home and the checklist. */
internal fun NavGraphBuilder.setupDestinations(
    nav: NavHostController,
    prefs: AppPrefs,
    repo: SetupRepo,
    padding: PaddingValues,
) {
    composable(Routes.AddEquipment) {
        Box(Modifier.padding(padding)) { AddEquipmentRoute(nav, prefs, repo) }
    }
    composable(
        Routes.EquipmentPattern,
        arguments = listOf(navArgument(Routes.EquipmentIdArg) { type = NavType.StringType }),
    ) { e ->
        val id = e.arguments?.getString(Routes.EquipmentIdArg).orEmpty()
        Box(Modifier.padding(padding)) { EquipmentRoute(nav, repo, id) }
    }
    composable(
        Routes.DiagnosticsPattern,
        arguments = listOf(navArgument(Routes.EquipmentIdArg) { type = NavType.StringType }),
    ) { e ->
        val item = repo.setup.equipment(e.arguments?.getString(Routes.EquipmentIdArg).orEmpty())
        val recorder = RecorderClient.get(LocalContext.current)
        Box(Modifier.padding(padding)) {
            if (item == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                RaceBoxDiagnosticsRoute(item, recorder, onBack = { nav.popBackStack() })
            }
        }
    }
    composable(
        Routes.CarPattern,
        arguments = listOf(navArgument(Routes.CarIdArg) { type = NavType.StringType }),
    ) { e ->
        val id = e.arguments?.getString(Routes.CarIdArg).orEmpty()
        Box(Modifier.padding(padding)) { CarRoute(nav, repo, id) }
    }
}

/**
 * Add equipment (UI-34 option B). Scans for RaceBoxes and GoPro HERO11s while open, if the
 * Bluetooth permissions are granted. A RaceBox is added at once; the recorder connects to it when a
 * session starts (FBBD-43). A HERO11 is paired first, which needs it in pairing mode (FBBD-22).
 */
@Composable
internal fun AddEquipmentRoute(nav: NavHostController, prefs: AppPrefs, repo: SetupRepo) {
    val context = LocalContext.current
    val searching = remember { prefs.hasRequiredPermissions() }
    val found by remember(context) {
        if (searching) {
            // Each scanner emits only once it hears its own kind, so both start empty: a RaceBox
            // shows even with no GoPro nearby.
            combine(
                scanRaceBoxes(context).onStart { emit(emptyList()) },
                scanGoPros(context).onStart { emit(emptyList()) },
            ) { boxes, goPros ->
                boxes.map { NearbyDevice(EquipmentModel.RACEBOX_MICRO, it.name, it.address, it.bars) } +
                    goPros.map { NearbyDevice(EquipmentModel.GOPRO_HERO11, it.name, it.address, it.bars) }
            }
        } else {
            flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val setup = repo.setup
    val listed = setup.equipment.mapNotNull { it.address }.toSet()
    val addItem: (EquipmentModel, String, String?) -> Unit = { model, name, address ->
        repo.update { it.addEquipment(model, name, address, ::newSetupId) }
        // A HERO11 pairs for a while; Arek may have left this screen meanwhile.
        if (nav.currentDestination?.route == Routes.AddEquipment) nav.popBackStack()
    }
    AddEquipmentScreen(
        nearby = found.filter { it.address !in listed }.sortedByDescending { it.bars },
        searching = searching,
        withoutBluetooth = buildList {
            if (setup.equipment.none { it.model == EquipmentModel.PHONE_CAMERA }) add(EquipmentModel.PHONE_CAMERA)
            if (setup.equipment.none { it.model == EquipmentModel.GOPRO_HERO8 }) add(EquipmentModel.GOPRO_HERO8)
            if (setup.equipment.none { it.model == EquipmentModel.DEMO_GNSS }) add(EquipmentModel.DEMO_GNSS)
        },
        busyAddress = busy,
        error = error,
        onAddNearby = { d ->
            if (d.model == EquipmentModel.GOPRO_HERO11) {
                busy = d.address
                error = null
                scope.launch {
                    try {
                        GoPros.get(context).pair(d.address)
                        addItem(d.model, d.name, d.address)
                    } catch (e: IOException) {
                        error = "Pairing failed: ${e.message}"
                    } finally {
                        busy = null
                    }
                }
            } else {
                addItem(d.model, d.name, d.address)
            }
        },
        onAddModel = { m -> addItem(m, m.label, null) },
        onBack = { nav.popBackStack() },
    )
}

/** One equipment item (UI-33 B). Removing it drops it from the session choice too. */
@Composable
internal fun EquipmentRoute(nav: NavHostController, repo: SetupRepo, id: String) {
    val item = repo.setup.equipment(id)
    if (item == null) {
        // Removed, or a stale back stack: nothing to show.
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    EquipmentScreen(
        item = item,
        onRename = { name -> repo.update { it.renameEquipment(id, name) } },
        onDiagnostics = { nav.navigate(Routes.diagnostics(id)) },
        onRemove = {
            nav.popBackStack()
            repo.update { it.removeEquipment(id) }
        },
        onBack = { nav.popBackStack() },
        onChooseVideo = { mode -> repo.update { it.chooseVideo(id, mode) } },
    )
}

/** Adding ([Routes.NewCar]) or editing a car (UI-35 B). */
@Composable
internal fun CarRoute(nav: NavHostController, repo: SetupRepo, id: String) {
    val car = repo.setup.car(id)
    if (car == null && id != Routes.NewCar) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    CarScreen(
        car = car,
        chosen = car != null && repo.setup.session.carId == car.id,
        onSave = { name, notes ->
            repo.update { s ->
                if (car == null) {
                    s.addCar(name, notes, ::newSetupId)
                } else {
                    s.updateCar(car.copy(name = name, notes = notes))
                }
            }
            nav.popBackStack()
        },
        onChoose = {
            repo.update { it.chooseCar(id) }
            nav.popBackStack()
        },
        onRemove = {
            nav.popBackStack()
            repo.update { it.removeCar(id) }
        },
        onBack = { nav.popBackStack() },
    )
}
