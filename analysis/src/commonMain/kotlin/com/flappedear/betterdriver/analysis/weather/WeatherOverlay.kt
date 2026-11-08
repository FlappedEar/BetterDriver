package com.flappedear.betterdriver.analysis.weather

import com.flappedear.betterdriver.timing.overlay.HudWeather
import com.flappedear.betterdriver.timing.overlay.OverlayWeather
import com.flappedear.betterdriver.timing.overlay.Sky
import com.flappedear.betterdriver.timing.overlay.TrackSurface

/**
 * The weather for the video's weather plate (FBBD-471): each 15-minute value from the session's
 * start at [startUtcMillis] (session time 0) on, by session time, with the surface the rain of the
 * two hours before left. Values from before the start are kept too, so a moment always has the
 * quarter hour it falls in.
 */
fun SessionWeather.overlay(startUtcMillis: Long): OverlayWeather = OverlayWeather(
    readings.sortedBy { it.utcMillis }.map { r ->
        (r.utcMillis - startUtcMillis) * MicrosPerMilli to HudWeather(
            airC = r.airC,
            sky = r.code?.let(Sky::of),
            surface = surfaceAt(r.utcMillis)?.let(::trackSurface),
            windKmh = r.windKmh,
            windFromDeg = r.windFromDeg,
            humidityPct = r.humidityPct,
        )
    },
)

/** The weather plate's data of the session in [dir] that started at [startUtcMillis]; null without weather. */
fun overlayWeather(dir: String, startUtcMillis: Long): OverlayWeather? =
    readWeather(dir)?.overlay(startUtcMillis)?.takeUnless { it.isEmpty }

private fun trackSurface(s: Surface): TrackSurface = when (s) {
    Surface.Dry -> TrackSurface.Dry
    Surface.Damp -> TrackSurface.Damp
    Surface.Wet -> TrackSurface.Wet
}

private const val MicrosPerMilli = 1000L
