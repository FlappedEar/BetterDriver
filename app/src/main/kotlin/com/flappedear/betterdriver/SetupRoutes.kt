package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import com.flappedear.betterdriver.core.ble.FoundBleDevice
import com.flappedear.betterdriver.core.ble.diy.scanDiyDevices
import com.flappedear.betterdriver.core.ble.dragy.scanDragys
import com.flappedear.betterdriver.core.ble.hr.scanHeartRateMonitors
import com.flappedear.betterdriver.core.ble.lns.scanLnsDevices
import com.flappedear.betterdriver.core.ble.nmea.bondedSppDevices
import com.flappedear.betterdriver.core.ble.scanRaceBoxes
import com.flappedear.betterdriver.core.ble.vbox.bondedVboxSports
import com.flappedear.betterdriver.gopro.GoPros
import com.flappedear.betterdriver.gopro.scanGoPros
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.shell.AddEquipmentScreen
import com.flappedear.betterdriver.shell.AppPrefs
import com.flappedear.betterdriver.shell.CarScreen
import com.flappedear.betterdriver.shell.EquipmentScreen
import com.flappedear.betterdriver.shell.NearbyDevice
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.shell.newSetupId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * Add equipment (UI-34 option B). Scans for RaceBoxes (Mini, Mini S, Micro) and Open GoPro cameras
 * (HERO9 and later) while open, if the Bluetooth permissions are granted. A RaceBox is added at
 * once; the recorder connects to it when a session starts (FBBD-43). A GoPro is paired first,
 * which needs it in pairing mode, and tells the app which camera it is (FBBD-22, FBBD-105). Any
 * number of GoPros can be added.
 */
@Composable
internal fun AddEquipmentRoute(nav: NavHostController, prefs: AppPrefs, repo: SetupRepo) {
    val context = LocalContext.current
    val searching = remember { prefs.hasRequiredPermissions() }
    val found by rememberNearby(searching)
    val pairedGps = rememberPairedGps(searching)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val setup = repo.setup
    val listed = setup.equipment.mapNotNull { it.address }.toSet()
    val addItem: (EquipmentModel, String, String?, GoProModel?) -> Unit = { model, name, address, goPro ->
        repo.update { it.addEquipment(model, name, address, ::newSetupId, goPro) }
        // A GoPro pairs for a while; Arek may have left this screen meanwhile.
        if (nav.currentDestination?.route == Routes.AddEquipment) nav.popBackStack()
    }
    AddEquipmentScreen(
        nearby = found.filter { it.address !in listed }.sortedByDescending { it.bars },
        searching = searching,
        withoutBluetooth = withoutBluetooth(setup),
        busyAddress = busy,
        error = error,
        onAddNearby = { d ->
            if (d.model == EquipmentModel.GOPRO) {
                busy = d.address
                error = null
                scope.launch {
                    try {
                        val paired = GoPros.get(context).pair(d.address)
                        addItem(d.model, d.name, d.address, paired.model)
                    } catch (e: IOException) {
                        error = "Pairing failed: ${e.message}"
                    } finally {
                        busy = null
                    }
                }
            } else {
                addItem(d.model, d.name, d.address, null)
            }
        },
        onAddModel = { m, goPro -> addItem(m, goPro?.let(::goProName) ?: m.label, null, goPro) },
        onBack = { nav.popBackStack() },
        pairedGps = pairedGps.filter { it.address !in listed },
        onAddWifiGps = { address -> addItem(EquipmentModel.TCP_GNSS, EquipmentModel.TCP_GNSS.label, address, null) },
    )
}

/**
 * Bluetooth GPS receivers paired in Android's settings: VBOX Sports (FBBD-116) and NMEA receivers
 * (FBBD-108). Classic receivers are paired there, so they are listed, not scanned. A VBOX Sport is
 * listed only as one. Read once per screen, and only with the permissions.
 */
@Composable
private fun rememberPairedGps(allowed: Boolean): List<NearbyDevice> {
    val context = LocalContext.current
    return remember(allowed) {
        val vboxes = if (allowed) bondedVboxSports(context) else emptyList()
        val vboxAddresses = vboxes.map { it.address }.toSet()
        val nmea = if (allowed) bondedSppDevices(context).filter { it.address !in vboxAddresses } else emptyList()
        vboxes.map { NearbyDevice(EquipmentModel.VBOX_SPORT, it.name, it.address, 0) } +
            nmea.map { NearbyDevice(EquipmentModel.NMEA_GNSS, it.name, it.address, 0) }
    }
}

/** The first name of a GoPro added by hand: "GoPro HERO7 Black", or "GoPro" when Arek did not say which. */
private fun goProName(goPro: GoProModel): String = when (goPro) {
    GoProModel.OTHER, GoProModel.OTHER_OPEN_GOPRO -> "GoPro"
    else -> "GoPro ${goPro.label}"
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
        onChooseGoPro = { m -> repo.update { it.chooseGoPro(id, m) } },
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

/**
 * What can be added without Bluetooth: the phone camera and the demo receiver once, cameras run by
 * hand and Wi-Fi receivers any number of times.
 */
private fun withoutBluetooth(setup: Setup): List<EquipmentModel> = buildList {
    if (setup.equipment.none { it.model == EquipmentModel.PHONE_CAMERA }) add(EquipmentModel.PHONE_CAMERA)
    add(EquipmentModel.GOPRO_FILES)
    add(EquipmentModel.CAMERA_FILES)
    add(EquipmentModel.TCP_GNSS)
    if (setup.equipment.none { it.model == EquipmentModel.DEMO_GNSS }) add(EquipmentModel.DEMO_GNSS)
}

/**
 * What a scan hears nearby while [searching]: RaceBoxes, Open GoPro cameras, Bluetooth LE GPS
 * receivers (FBBD-114), RaceChrono DIY devices (FBBD-118), Dragys (FBBD-117) and heart-rate
 * monitors (FBBD-119). Each scanner emits only once it hears its own kind, so each starts empty: a
 * RaceBox shows even with no GoPro nearby.
 */
@Composable
private fun rememberNearby(searching: Boolean): State<List<NearbyDevice>> {
    val context = LocalContext.current
    return remember(context) {
        if (searching) {
            val scans: List<Flow<List<NearbyDevice>>> = listOf(
                scanRaceBoxes(context).map { found ->
                    found.map { NearbyDevice(EquipmentModel.RACEBOX, it.name, it.address, it.bars) }
                },
                scanGoPros(context).map { found ->
                    found.map { NearbyDevice(EquipmentModel.GOPRO, it.name, it.address, it.bars) }
                },
                scanLnsDevices(context).map { found -> found.map { nearby(EquipmentModel.LNS_GNSS, it) } },
                scanHeartRateMonitors(context).map { found -> found.map { nearby(EquipmentModel.HEART_RATE, it) } },
                scanDiyDevices(context).map { found -> found.map { nearby(EquipmentModel.DIY_GNSS, it) } },
                scanDragys(context).map { found ->
                    found.map { nearby(EquipmentModel.DRAGY, FoundBleDevice(it.name, it.address, it.rssi)) }
                },
            )
            // Six scans: more than combine's typed overloads take, so the lists are combined as one.
            combine(scans.map { it.onStart { emit(emptyList()) } }) { lists -> lists.flatMap { it } }
        } else {
            flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())
}

/** A standard-service device; one that advertises no name is shown by its model and address. */
private fun nearby(model: EquipmentModel, d: FoundBleDevice) =
    NearbyDevice(model, d.name.ifBlank { "${model.label} ${d.address}" }, d.address, d.bars)
