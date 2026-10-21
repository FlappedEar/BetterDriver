package com.flappedear.betterdriver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flappedear.betterdriver.recording.RecorderClient
import com.flappedear.betterdriver.shell.CarPage
import com.flappedear.betterdriver.shell.NoCarPage
import com.flappedear.betterdriver.shell.SetupRepo

/**
 * The Car destination in the bottom bar (UI-136 B): the next session's car's page, with its live
 * values (UI-133 B); and a car's page opened from Settings › Cars.
 */
internal fun NavGraphBuilder.carDestinations(nav: NavHostController, repo: SetupRepo, padding: PaddingValues) {
    composable(Routes.Car) {
        Box(Modifier.padding(padding)) { CarRoute(nav, repo, id = null) }
    }
    composable(
        Routes.CarPagePattern,
        arguments = listOf(navArgument(Routes.CarIdArg) { type = NavType.StringType }),
    ) { e ->
        val id = e.arguments?.getString(Routes.CarIdArg).orEmpty()
        Box(Modifier.padding(padding)) {
            if (repo.setup.car(id) == null) PopIfShown(nav, e) else CarRoute(nav, repo, id)
        }
    }
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
    CarPage(
        car = car,
        chosen = chosen,
        // Only the next session's car connects: the adapter reads the car it is plugged into.
        obd = if (chosen) rememberObdView(recorder) else null,
        onEdit = { nav.navigate(Routes.car(car.id)) },
        onChoose = { repo.update { it.chooseCar(car.id) } },
        onBack = if (id == null) null else ({ nav.popBackStack() }),
    )
}
