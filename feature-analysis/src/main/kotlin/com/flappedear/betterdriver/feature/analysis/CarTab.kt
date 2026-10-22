package com.flappedear.betterdriver.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.analysis.vehicle.PedalMinimumSpeedKmh
import com.flappedear.betterdriver.analysis.vehicle.PedalShares
import com.flappedear.betterdriver.analysis.vehicle.VehicleChannel
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import kotlin.math.roundToInt

/** The Car tab's cards (FBBD-176): pedals, temperatures and heart rate, each only when recorded. */
@Composable
internal fun CarCards(car: CarUi?, modifier: Modifier) {
    if (car == null || car.empty) {
        Card(modifier.testTag(CarEmptyTag)) {
            Dim("No car data or heart rate in this session: no OBD adapter or heart rate strap recorded it.")
        }
        return
    }
    PedalCard(car, modifier)
    TemperatureCard(car, modifier)
    HeartRateChartCard(car.heartRate, modifier)
}

@Composable
private fun RowName(row: CarRow) {
    val c = TrackTheme.colors
    if (row.lapNumber == null) {
        Dim("Whole drive", Modifier.width(WholeDriveWidth))
        return
    }
    Dim("Lap ${row.lapNumber}", Modifier.width(LapNameWidth))
    Text(
        row.millis?.let(::formatLapTime).orEmpty(),
        color = if (row.best) c.best else c.text,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.width(LapTimeWidth),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** UI-158 C: one bar per lap, full throttle, part throttle, coasting and braking. */
@Composable
private fun PedalCard(car: CarUi, modifier: Modifier) {
    val c = TrackTheme.colors
    Card(modifier.testTag(PedalCardTag)) {
        TrackLabel(if (car.rows.any { it.lapNumber != null }) "Pedals by lap" else "Pedals · whole drive")
        if (!car.hasPedals) {
            Dim("Not recorded in this session")
            return@Card
        }
        val withBrake = car.rows.any { it.pedals?.braking != null }
        for (row in car.rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowName(row)
                val pedals = row.pedals
                if (pedals == null) Dim("Not recorded") else PedalBar(pedals, Modifier.weight(1f))
            }
        }
        val keys = if (withBrake) {
            listOf(c.gain to "Full", c.gain.copy(alpha = PartAlpha) to "Part", c.textDim to "Coast", c.loss to "Brake")
        } else {
            listOf(c.gain to "Full", c.gain.copy(alpha = PartAlpha) to "Part", c.textDim to "Off throttle")
        }
        Key(keys)
        Dim("Share of the time above ${PedalMinimumSpeedKmh.roundToInt()} km/h. Full = pedal 90 % or more.")
    }
}

@Composable
private fun PedalBar(p: PedalShares, modifier: Modifier) {
    val c = TrackTheme.colors
    val parts = listOfNotNull(
        p.full to c.gain,
        p.part to c.gain.copy(alpha = PartAlpha),
        p.coasting to c.textDim,
        p.braking?.let { it to c.loss },
    )
    val text = "full ${pct(p.full)}, part ${pct(p.part)}, coast ${pct(p.coasting)}" +
        (p.braking?.let { ", brake ${pct(it)}" } ?: "")
    Canvas(
        modifier
            .height(BarHeight)
            .clip(RoundedCornerShape(3.dp))
            .semantics { contentDescription = text },
    ) {
        var x = 0f
        for ((share, color) in parts) {
            val w = (share.toFloat() * size.width).coerceAtLeast(0f)
            if (w > 0f) drawRect(color, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

/** UI-159 B: the lowest and highest of each recorded temperature, per lap. */
@Composable
private fun TemperatureCard(car: CarUi, modifier: Modifier) {
    val kinds = car.temperatureKinds
    Card(modifier.testTag(TemperatureCardTag)) {
        val byLap = car.rows.any { it.lapNumber != null }
        TrackLabel(if (byLap) "Temperatures by lap · min–max" else "Temperatures · min–max")
        if (kinds.isEmpty()) {
            Dim("Not recorded in this session")
            return@Card
        }
        // One unit over the table when every temperature has it, else each column names its own.
        val units = car.rows.flatMap { it.temperatures }.groupBy({ it.kind }, { it.unit })
            .mapValues { (_, u) -> u.first() }
        val shared = units.values.distinct().singleOrNull()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackLabel(shared.orEmpty(), Modifier.weight(1f))
            for (k in kinds) {
                val name = TemperatureNames.getValue(k)
                TrackLabel(
                    if (shared == null) "$name ${units.getValue(k)}" else name,
                    Modifier.width(TemperatureWidth).padding(start = 4.dp),
                )
            }
        }
        for (row in car.rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only the lap's number: four columns of temperatures leave no room for its time on a phone.
                Text(
                    row.lapNumber?.let { "Lap $it" } ?: "Whole drive",
                    color = if (row.best) TrackTheme.colors.best else TrackTheme.colors.textDim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                for (k in kinds) {
                    val r = row.temperatures.firstOrNull { it.kind == k }
                    Text(
                        r?.let { "${it.min.roundToInt()}–${it.max.roundToInt()}" } ?: "—",
                        color = if (r == null) TrackTheme.colors.textDim else TrackTheme.colors.text,
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(TemperatureWidth),
                    )
                }
            }
        }
        val missing = VehicleChannel.Temperatures.filter { it !in kinds }
        if (missing.isNotEmpty()) {
            Dim("Not recorded: " + missing.joinToString(", ") { TemperatureNames.getValue(it).lowercase() })
        }
    }
}

/** UI-160 A: the heart rate figures with a chart of the session under them, laps shaded, gaps blank. */
@Composable
private fun HeartRateChartCard(hr: HeartRateChartUi?, modifier: Modifier) {
    Card(modifier.testTag(HeartRateChartTag)) {
        TrackLabel(hr?.stats?.device?.let { "Heart rate · $it" } ?: "Heart rate")
        if (hr == null) {
            Dim("Not recorded in this session")
            return@Card
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val figures = listOf("Average" to hr.stats.averageBpm, "Max" to hr.stats.maxBpm, "Min" to hr.stats.minBpm)
            for ((label, bpm) in figures) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = TrackTheme.colors.textDim)) { append("$label ") }
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$bpm") }
                        withStyle(SpanStyle(color = TrackTheme.colors.textDim)) { append(" bpm") }
                    },
                    color = TrackTheme.colors.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        HeartRateChart(hr, Modifier.fillMaxWidth().height(ChartHeight))
        val laps = if (hr.laps.isEmpty()) "" else " Laps shaded."
        Dim("Whole session, ${hr.stats.minutes} min.$laps Gaps stay blank.")
    }
}

@Composable
private fun HeartRateChart(hr: HeartRateChartUi, modifier: Modifier) {
    val c = TrackTheme.colors
    val shade = MaterialTheme.colorScheme.surfaceVariant
    val values = hr.segments.flatten().map { it.value }
    val low = (values.min() - ChartPaddingBpm)
    val high = (values.max() + ChartPaddingBpm)
    val span = (hr.toSeconds - hr.fromSeconds).coerceAtLeast(1.0)
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(ChartHeight)
                .semantics {
                    contentDescription = "Heart rate over the session, ${hr.stats.minBpm} to ${hr.stats.maxBpm} bpm"
                },
        ) {
            fun x(t: Double) = ((t - hr.fromSeconds) / span).toFloat() * size.width
            fun y(v: Double) = size.height - ((v - low) / (high - low)).toFloat() * size.height
            hr.laps.forEachIndexed { i, (from, to) ->
                if (i % 2 == 0) drawRect(shade, Offset(x(from), 0f), Size(x(to) - x(from), size.height))
            }
            for (segment in hr.segments) {
                if (segment.size == 1) {
                    drawCircle(c.loss, LineWidth.toPx(), Offset(x(segment[0].time), y(segment[0].value)))
                    continue
                }
                val path = Path()
                segment.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(x(p.time), y(p.value)) else path.lineTo(x(p.time), y(p.value))
                }
                drawPath(path, c.loss, style = Stroke(width = LineWidth.toPx()))
            }
        }
        Text(
            "${hr.stats.maxBpm}",
            color = c.textDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            "${hr.stats.minBpm}",
            color = c.textDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
private fun Key(keys: List<Pair<Color, String>>) {
    Text(
        buildAnnotatedString {
            keys.forEachIndexed { i, (color, name) ->
                if (i > 0) append("  ")
                withStyle(SpanStyle(color = color)) { append("■ ") }
                append(name)
            }
        },
        color = TrackTheme.colors.textDim,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun pct(share: Double) = "${(share * Percent).roundToInt()} %"

private const val Percent = 100.0

private val TemperatureNames = mapOf(
    VehicleChannel.OilTemp to "Oil",
    VehicleChannel.CoolantTemp to "Coolant",
    VehicleChannel.GearboxTemp to "Gearbox",
    VehicleChannel.IntakeTemp to "Intake",
    VehicleChannel.Rpm to "RPM",
)

internal const val PedalCardTag = "car-pedals"
internal const val TemperatureCardTag = "car-temperatures"
internal const val HeartRateChartTag = "car-heart-rate"
internal const val CarEmptyTag = "car-empty"

private const val PartAlpha = 0.5f
private const val ChartPaddingBpm = 5.0
private val BarHeight = 12.dp
private val ChartHeight = 120.dp
private val LineWidth = 1.5.dp
private val LapNameWidth = 48.dp
private val LapTimeWidth = 72.dp
private val WholeDriveWidth = 120.dp
private val TemperatureWidth = 66.dp
