package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.weather.WeatherUi
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel

/** This state with the Weather card, once the session's weather is read ([weather] not null). */
fun AnalysisState.withWeather(weather: WeatherUi?): AnalysisState = when {
    weather == null -> this
    this is AnalysisState.Ready -> AnalysisState.Ready(ui.copy(weather = weather))
    this is AnalysisState.Unavailable -> copy(weather = weather)
    else -> this
}

/**
 * The session's weather (FBBD-403), in the look of the Heart rate card: the air, the wind and the
 * surface, the sky and the humidity, then how it compares with the last time at the same place, so a
 * lap time is read with the conditions it was driven in. A damp or wet surface is drawn in the loss
 * colour. The credit is what the data's licence asks for.
 */
@Composable
internal fun WeatherCard(ui: WeatherUi, modifier: Modifier = Modifier) {
    val c = TrackTheme.colors
    Card(modifier.testTag("weather")) {
        TrackLabel(ui.sky?.let { "Weather · $it" } ?: "Weather")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val humidity = ui.humidity?.let { "$it humidity" }.orEmpty()
            Figure("Air", ui.air ?: "–", humidity, c.text, Modifier.weight(1f), WeatherSize)
            Figure(
                "Track",
                ui.surface?.substringBefore(':') ?: "–",
                "by the rain",
                if (ui.wet) c.loss else c.text,
                Modifier.weight(1f),
                WeatherSize,
            )
        }
        ui.wind?.let { Dim("Wind $it") }
        ui.surface?.takeIf { ':' in it }?.let { Dim(it, color = c.loss) }
        ui.versus?.let { Dim(it, color = c.text) }
        Dim(ui.credit)
    }
}

private val WeatherSize = 22.dp

internal val PreviewWeather = WeatherUi(
    sky = "Partly cloudy",
    air = "11–13 °C",
    wind = "18 km/h from W, gusts 35",
    surface = "Damp: rain in the 2 h before",
    wet = true,
    humidity = "82 %",
    versus = "Last time here (12 Oct): 17 °C, dry. Today 5 °C colder, damp.",
)

@Preview(widthDp = 400, heightDp = 400)
@Composable
private fun WeatherCardPreview() {
    BetterDriverTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            WeatherCard(PreviewWeather)
        }
    }
}
