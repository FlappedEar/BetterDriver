package com.flappedear.betterdriver.feature.track

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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
