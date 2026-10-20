package com.flappedear.betterdriver.recording

import com.flappedear.betterdriver.core.ui.format.formatDelta
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.feature.track.NoTileValue
import com.flappedear.betterdriver.feature.track.TileChoice
import com.flappedear.betterdriver.feature.track.TileChoiceGroup
import com.flappedear.betterdriver.feature.track.TrackTile
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.setup.TileIds
import com.flappedear.betterdriver.timing.live.LiveTimingState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The channels a track screen tile can show (FBBD-262) and how each reads: label and unit as the
 * tile and its list show them. OBD-II channels are the generic mode 01 PIDs; a channel the
 * recorder names that is not among them still shows, under a name made from its id.
 */
private class TileChannel(val id: String, val label: String, val unit: String)

private val TimingChannels = listOf(
    TileChannel(TileIds.DELTA, "Delta", "s"),
    TileChannel(TileIds.LAP_TIME, "Lap time", ""),
    TileChannel(TileIds.LAST_LAP, "Last lap", ""),
    TileChannel(TileIds.BEST_LAP, "Best lap", ""),
    TileChannel(TileIds.PREDICTED_LAP, "Predicted", ""),
    TileChannel(TileIds.LAP_NUMBER, "Lap", ""),
)

private val GnssChannels = listOf(
    TileChannel(TileIds.SPEED, "Speed", "km/h"),
    TileChannel(TileIds.G, "G", "g"),
    TileChannel(TileIds.SATELLITES, "Satellites", ""),
)

private val ObdChannels = StandardPids.all.map { TileChannel(TileIds.obd(it.id), it.name, it.unit) }

private val HeartRate = TileChannel(TileIds.HEART_RATE, "Heart rate", "bpm")

private val Known: Map<String, TileChannel> =
    (TimingChannels + GnssChannels + ObdChannels + HeartRate).associateBy { it.id }

private fun channel(id: String): TileChannel = Known[id] ?: TileChannel(
    id,
    id.removePrefix(TileIds.OBD_PREFIX).replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
    "",
)

/** What a tile's channel needs from the session, to show or to leave it out (UI-126 B). */
internal class TileSources(
    val live: LiveSnapshot?,
    val timing: LiveTimingState?,
    /** The session's receiver gives G (a RaceBox): without it the G tile is left out. */
    val receiverHasG: Boolean,
)

/** The tile for slot [id], null for an empty slot. */
internal fun trackTile(id: String?, s: TileSources): TrackTile? {
    if (id == null) return null
    val ch = channel(id)
    val label = if (id == TileIds.LAP_NUMBER && s.timing?.pointToPoint == true) "Run" else ch.label
    return TrackTile(label, tileValue(ch, s) ?: NoTileValue, ch.unit, id = id, hasData = hasData(id, s))
}

/** The channel's value now as the tile shows it, or null when it has none. */
private fun tileValue(ch: TileChannel, s: TileSources): String? {
    val live = s.live
    val timing = s.timing
    // The receiver's last speed stays known after the fix is lost; it is not the car's speed now.
    val fix = live?.takeIf { it.gnss == GnssState.Ok }
    return when (val id = ch.id) {
        TileIds.SPEED -> fix?.speedKmh?.roundToInt()?.toString()
        TileIds.G -> live?.gCombined?.let { String.format(Locale.ROOT, "%.2f", it) }
        TileIds.SATELLITES -> fix?.sats?.toString()
        TileIds.DELTA -> timing?.deltaSeconds?.let(::formatDelta)
        TileIds.LAP_TIME -> timing?.lapMillis?.let(::formatLapTime)
        TileIds.LAST_LAP -> timing?.lastMillis?.let(::formatLapTime)
        TileIds.BEST_LAP -> timing?.bestMillis?.let(::formatLapTime)
        TileIds.PREDICTED_LAP -> timing?.predictedMillis?.let(::formatLapTime)
        TileIds.LAP_NUMBER -> timing?.lapNumber?.takeIf { it > 0 }?.toString()
        else -> live?.channels?.get(id)?.let { v -> if (v.isFinite()) formatValue(v, ch.unit) else null }
    }
}

/**
 * Whether [id] can have a value in this session: speed and satellites always (the receiver is
 * required), G with a receiver that gives it, timing at a track with lines, and car and body
 * channels once they have sent a value.
 */
private fun hasData(id: String, s: TileSources): Boolean = when (id) {
    TileIds.SPEED, TileIds.SATELLITES -> true
    TileIds.G -> s.receiverHasG
    in TimingIds -> s.timing != null
    else -> s.live?.channels?.containsKey(id) == true
}

private val TimingIds = TimingChannels.map { it.id }.toSet()

/** Decimals by unit: volts and air flow to a tenth, lambda to a hundredth, the rest whole. */
private fun formatValue(v: Double, unit: String): String {
    val decimals = when (unit) {
        "V", "g/s" -> 1
        "λ" -> 2
        else -> 0
    }
    return String.format(Locale.ROOT, "%.${decimals}f", v)
}

/**
 * A tile's list (UI-124 A): timing, the receiver's channels, every OBD-II channel and heart rate,
 * each with its value now. [receiver] names the GNSS group; a channel without data is dimmed.
 */
internal fun tileChoices(s: TileSources, receiver: String?): List<TileChoiceGroup> {
    fun choices(list: List<TileChannel>) = list.map { ch ->
        val tile = trackTile(ch.id, s)
        val value = when {
            tile == null || !tile.hasData -> "No data"
            tile.value == NoTileValue -> NoTileValue
            tile.unit.isEmpty() || tile.unit.equals(tile.label, ignoreCase = true) -> tile.value
            else -> "${tile.value} ${tile.unit}"
        }
        TileChoice(ch.id, ch.label, value, hasData = tile?.hasData == true)
    }
    // Car channels the recorder sends that are not generic PIDs, after the generic ones.
    val otherObd = s.live?.channels?.keys.orEmpty()
        .filter { it.startsWith(TileIds.OBD_PREFIX) && it !in Known }
        .sorted()
        .map(::channel)
    return listOf(
        TileChoiceGroup("Timing", choices(TimingChannels)),
        TileChoiceGroup(listOfNotNull("GPS", receiver).joinToString(" · "), choices(GnssChannels)),
        TileChoiceGroup("Car · OBD-II", choices(ObdChannels + otherObd)),
        TileChoiceGroup("You · heart rate", choices(listOf(HeartRate))),
    )
}
