package com.flappedear.betterdriver.feature.track

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.PowerManager
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Window settings the track screen holds while it is shown.

@Composable
internal fun KeepScreenOn(view: View) {
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Screen brightness while the track screen is shown (FBBD-63). */
enum class TrackBrightness(val label: String) {
    /** Full brightness, so the screen reads in sunlight. */
    Max("Max"),

    /** The phone's own brightness, automatic or not. */
    System("System"),
}

/**
 * Holds the window at full brightness with [TrackBrightness.Max], and gives back the window's
 * own value when the track screen goes. [TrackBrightness.System] leaves the window alone.
 */
@Composable
internal fun LockBrightness(activity: Activity?, brightness: TrackBrightness = TrackBrightness.Max) {
    DisposableEffect(activity, brightness) {
        val window = activity?.window
        if (window == null || brightness != TrackBrightness.Max) return@DisposableEffect onDispose {}
        val saved = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = saved }
        }
    }
}

@Composable
internal fun LockLandscape(activity: Activity?) {
    // Saved across the recreation that the rotation causes, so exit restores
    // the orientation from before the track screen, not the forced landscape.
    val originalOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            // A rotation recreates the activity; restoring here would rotate it back in a loop.
            if (activity != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }
}

/**
 * Asks Android to hold the CPU and GPU at a speed they can keep up without heating, on phones that
 * support it, while the track screen is shown (FBBD-46). Peaks are lower, so the phone throttles and
 * steps the video down later. Tells [onReport] what it did, for the debug log.
 */
@Composable
internal fun SustainedPerformance(activity: Activity?, onReport: (String) -> Unit) {
    DisposableEffect(activity) {
        val window = activity?.window
        val power = activity?.getSystemService(PowerManager::class.java)
        val supported = power?.isSustainedPerformanceModeSupported == true
        if (window != null && supported) window.setSustainedPerformanceMode(true)
        onReport(if (supported) "sustained performance mode on" else "sustained performance mode not supported")
        onDispose { if (window != null && supported) window.setSustainedPerformanceMode(false) }
    }
}

/** One display mode: its id, size in pixels and refresh rate. */
internal data class RefreshMode(val id: Int, val width: Int, val height: Int, val hz: Float)

/**
 * The slowest mode at [current]'s size that still refreshes at [MinTrackHz] or more, or null when
 * none is slower than [current]. The track screen's data changes 25 times a second, so 60 Hz shows
 * every change, and on a 90 or 120 Hz screen the slower mode draws fewer frames.
 */
internal fun slowerRefresh(modes: List<RefreshMode>, current: RefreshMode): RefreshMode? =
    modes.filter { it.width == current.width && it.height == current.height && it.hz >= MinTrackHz - HzSlack }
        .minByOrNull { it.hz }
        ?.takeIf { it.hz < current.hz - HzSlack }

/**
 * Holds the window at a slower display refresh while the track screen is shown (FBBD-46), and gives
 * back the window's own choice when it goes. Tells [onReport] what it did, for the debug log.
 */
@Composable
internal fun SlowerRefresh(activity: Activity?, view: View, onReport: (String) -> Unit) {
    DisposableEffect(activity, view) {
        val window = activity?.window
        val display = view.display
        val current = display?.mode?.toRefreshMode()
        val modes = display?.supportedModes.orEmpty().map { it.toRefreshMode() }
        val slower = current?.let { slowerRefresh(modes, it) }
        if (window == null || slower == null) {
            onReport("display refresh kept at ${current?.hz ?: "unknown"} Hz")
            return@DisposableEffect onDispose {}
        }
        val saved = window.attributes.preferredDisplayModeId
        window.attributes = window.attributes.apply { preferredDisplayModeId = slower.id }
        onReport("display refresh ${current.hz} Hz -> ${slower.hz} Hz")
        onDispose { window.attributes = window.attributes.apply { preferredDisplayModeId = saved } }
    }
}

private fun Display.Mode.toRefreshMode() = RefreshMode(modeId, physicalWidth, physicalHeight, refreshRate)

/** The slowest refresh the track screen asks for. */
internal const val MinTrackHz = 60f

/** Refresh rates are reported as floats such as 59.94; this much counts as the same rate. */
private const val HzSlack = 0.5f

/** Light status and navigation bar icons on the dark track background. */
@Composable
internal fun LightSystemBarIcons(activity: Activity?, view: View) {
    DisposableEffect(activity, view) {
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            if (bars != null && lightStatus != null && lightNavigation != null) {
                bars.isAppearanceLightStatusBars = lightStatus
                bars.isAppearanceLightNavigationBars = lightNavigation
            }
        }
    }
}

/**
 * Hides the status and navigation bars while driving. On a phone in landscape
 * they take about 40 dp of the height (411 dp on a Pixel 6).
 */
@Composable
internal fun HideSystemBars(activity: Activity?, view: View) {
    DisposableEffect(activity, view) {
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
