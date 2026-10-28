package com.flappedear.betterdriver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flappedear.betterdriver.obd.PollSet
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.recording.isBroadcastCar
import com.flappedear.betterdriver.recording.obdTileChannels
import com.flappedear.betterdriver.setup.CarParameter
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.shell.CarCanPage
import com.flappedear.betterdriver.shell.CarPage
import com.flappedear.betterdriver.shell.CarPageActions
import com.flappedear.betterdriver.shell.CarPidsPage
import com.flappedear.betterdriver.shell.CarScanState
import com.flappedear.betterdriver.shell.DraftSaver
import com.flappedear.betterdriver.shell.NoCarPage
import com.flappedear.betterdriver.shell.OptionalDraftSaver
import com.flappedear.betterdriver.shell.ParameterDraft
import com.flappedear.betterdriver.shell.ParameterStep
import com.flappedear.betterdriver.shell.ParameterSteps
import com.flappedear.betterdriver.shell.SetupRepo
import com.flappedear.betterdriver.shell.ownChannelId
import com.flappedear.betterdriver.shell.readsFrsBroadcasts

/**
 * The Car destination in the bottom bar (UI-136 B): the next session's car's page, with its live
 * values (UI-133 B); and a car's page opened from Settings › Cars. Under it, what the car offers
 * and the driver's own parameters (FBBD-344).
 */
internal fun NavGraphBuilder.carDestinations(nav: NavHostController, repo: SetupRepo, padding: PaddingValues) {
    composable(Routes.Car) {
        Box(Modifier.padding(padding)) { CarRoute(nav, repo, id = null) }
    }
    composable(Routes.CarPagePattern, arguments = listOf(idArg())) { e ->
        val id = e.arguments?.getString(Routes.CarIdArg).orEmpty()
        Box(Modifier.padding(padding)) {
            if (repo.setup.car(id) == null) PopIfShown(nav, e) else CarRoute(nav, repo, id)
        }
    }
    composable(Routes.CarPidsPattern, arguments = listOf(idArg())) { e ->
        Box(Modifier.padding(padding)) { CarPidsRoute(nav, e, repo) }
    }
    composable(Routes.CarCanPattern, arguments = listOf(idArg())) { e ->
        Box(Modifier.padding(padding)) { CarCanRoute(nav, e, repo) }
    }
    composable(
        Routes.CarParameterPattern,
        arguments = listOf(
            idArg(),
            navArgument(Routes.CarParameterKeyArg) { type = NavType.StringType },
            navArgument(Routes.CarCanArg) {
                type = NavType.StringType
                nullable = true
            },
            navArgument(Routes.CarPidArg) {
                type = NavType.StringType
                nullable = true
            },
        ),
    ) { e ->
        Box(Modifier.padding(padding)) { ParameterRoute(nav, e, repo) }
    }
}

private fun idArg() = navArgument(Routes.CarIdArg) { type = NavType.StringType }

/** The car a sub-page is about, or null after it popped itself because the car is gone. */
@Composable
private fun carOf(nav: NavHostController, e: NavBackStackEntry, repo: SetupRepo): CarProfile? {
    val car = repo.setup.car(e.arguments?.getString(Routes.CarIdArg))
    if (car == null) PopIfShown(nav, e)
    return car
}

/**
 * The page of car [id], or of the next session's car when [id] is null (the bar's Car). While a
 * session records, the next session's car is the running session's.
 */
@Composable
private fun CarRoute(nav: NavHostController, repo: SetupRepo, id: String?) {
    val recorder = RecorderClient.get(LocalContext.current)
    val recording = recorder.state.recording || recorder.state.starting
    val setup = if (recording) repo.forSession() else repo.setup
    val nextId = setup.session.carId
    val car = setup.car(id ?: nextId)
    if (car == null) {
        NoCarPage(
            cars = setup.cars,
            onChoose = { c -> repo.update { it.chooseCar(c.id) } },
            onAdd = { nav.navigate(Routes.car(Routes.NewCar)) },
        )
        return
    }
    val chosen = car.id == nextId
    // Only the next session's car connects: the adapter reads the car it is plugged into.
    val link = if (chosen) rememberCarLink(recorder, car) else null
    CarPage(
        car = car,
        chosen = chosen,
        obd = link?.view,
        actions = CarPageActions(
            onEdit = { nav.navigate(Routes.car(car.id)) },
            onChoose = { repo.update { it.chooseCar(car.id) } },
            onBack = if (id == null) null else ({ nav.popBackStack() }),
            onPids = { nav.navigate(Routes.carPids(car.id)) },
            onCan = { nav.navigate(Routes.carCan(car.id)) },
            onAddParameter = { nav.navigate(Routes.carParameter(car.id)) },
            onParameter = { p -> nav.navigate(Routes.carParameter(car.id, p.key)) },
        ),
        scan = CarScanState(link?.scanning == true, link?.scanNote, link?.scan),
    )
}

/** The PIDs the car reported, with a switch each (UI-301 B); live values on the next session's car. */
@Composable
private fun CarPidsRoute(nav: NavHostController, e: NavBackStackEntry, repo: SetupRepo) {
    val car = carOf(nav, e, repo) ?: return
    val recorder = RecorderClient.get(LocalContext.current)
    val chosen = car.id == repo.setup.session.carId
    val link = if (chosen) rememberCarLink(recorder, car) else null
    val current = recordedPids(repo.setup, car)
    CarPidsPage(
        car = car,
        recorded = current,
        readings = link?.view?.status?.readings.orEmpty(),
        onSwitch = { pid, on ->
            changeCar(repo, car.id) { it.recordPid(pid, on, current) }
        },
        onAdd = { pid -> nav.navigate(Routes.carParameter(car.id, pid = pid)) },
        onBack = { nav.popBackStack() },
    )
}

/**
 * The PIDs a session with [car] records: the ones switched on, or before any switch what the
 * recorder picks of what the car reported (the pedals, RPM, temperatures and the tiles' channels).
 */
internal fun recordedPids(setup: Setup, car: CarProfile): Set<Int> {
    car.recordedPids?.let { return it.toSet() }
    val supported = car.scan?.supported?.toSet().orEmpty()
    return PollSet.Driver.plus(obdTileChannels(setup.trackScreens)).standardPids(supported)
}

@Composable
private fun CarCanRoute(nav: NavHostController, e: NavBackStackEntry, repo: SetupRepo) {
    val car = carOf(nav, e, repo) ?: return
    CarCanPage(
        car = car,
        frsCar = readsFrsBroadcasts(car, isBroadcastCar(car.name)),
        onAdd = { id -> nav.navigate(Routes.carParameter(car.id, can = id)) },
        onBack = { nav.popBackStack() },
    )
}

/**
 * Adding or changing one of the car's own parameters in steps (UI-302 B). On the next session's car
 * the draft can be tried on the car before it is saved: the page's connection reads it as one more
 * parameter. Back on a later step goes to the step before.
 */
@Composable
private fun ParameterRoute(nav: NavHostController, e: NavBackStackEntry, repo: SetupRepo) {
    val car = carOf(nav, e, repo) ?: return
    val key = e.arguments?.getString(Routes.CarParameterKeyArg) ?: Routes.NewParameter
    val existing = car.parameters.firstOrNull { it.key == key }
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(firstDraft(e, existing)) }
    var step by rememberSaveable { mutableStateOf(ParameterStep.Source) }
    // The draft as last sent to the car; a new one connects again.
    var tried by rememberSaveable(stateSaver = OptionalDraftSaver) { mutableStateOf<ParameterDraft?>(null) }
    val recorder = RecorderClient.get(LocalContext.current)
    val trial = tried?.let { it.copy(name = it.name.ifBlank { "Draft" }) }?.toParameter(DraftKey)
    val link = if (car.id == repo.setup.session.carId) rememberCarLink(recorder, car, trial) else null
    val canId = draft.canIdValue.takeIf { draft.source == CarParameterSource.CAN }
    val trying = tried != null && tried == draft
    val back: () -> Unit = {
        if (step == ParameterStep.Source) {
            nav.popBackStack()
        } else {
            step = ParameterStep.entries[step.ordinal - 1]
        }
        Unit
    }
    ParameterSteps(
        draft = draft,
        step = step,
        frame = car.scan?.canIds?.firstOrNull { it.id == canId },
        live = link?.view?.status?.readings?.firstOrNull { it.id == ownChannelId(DraftKey) }?.takeIf { trying },
        trying = trying,
        onChange = { draft = it },
        onStep = { step = it },
        onTry = link?.scan?.let { { tried = draft } },
        onSave = {
            draft.toParameter(existing?.key ?: car.newParameterKey(draft.name))?.let { saved ->
                changeCar(repo, car.id) { it.withParameter(saved) }
                nav.popBackStack()
            }
        },
        onRemove = existing?.let { p ->
            {
                nav.popBackStack()
                changeCar(repo, car.id) { it.withoutParameter(p.key) }
            }
        },
        onBack = back,
    )
    BackHandler(enabled = step != ParameterStep.Source, onBack = back)
}

/** The steps' fields to start from: the parameter changed, or a new one on the id or PID it was opened on. */
private fun firstDraft(e: NavBackStackEntry, existing: CarParameter?): ParameterDraft {
    val can = e.arguments?.getString(Routes.CarCanArg)?.toIntOrNull(Hex)
    val pid = e.arguments?.getString(Routes.CarPidArg)?.toIntOrNull(Hex)
    return when {
        existing != null -> ParameterDraft.of(existing)
        can != null -> ParameterDraft.can(can)
        pid != null -> ParameterDraft.pid(pid)
        else -> ParameterDraft()
    }
}

/** Saves [change] to car [id], if it is still there. */
private fun changeCar(repo: SetupRepo, id: String, change: (CarProfile) -> CarProfile) =
    repo.update { s -> s.car(id)?.let { s.updateCar(change(it)) } ?: s }

private const val Hex = 16
