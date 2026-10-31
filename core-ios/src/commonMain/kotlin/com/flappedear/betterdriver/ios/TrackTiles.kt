package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.format.formatDelta
import com.flappedear.betterdriver.format.formatLapTime
import com.flappedear.betterdriver.obd.CanBroadcasts
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveSnapshot
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.TileIds
import com.flappedear.betterdriver.setup.TrackPage
import com.flappedear.betterdriver.timing.live.LiveTimingState
import kotlin.math.roundToInt

/** One data tile on the iPhone's track screen (FBBD-262): [hasData] false is left out while driving. */
data class ScreenTile(val id: String, val label: String, val value: String, val unit: String, val hasData: Boolean)

/** One channel in a tile's list (UI-124 A), with its value now, or "No data". */
data class TileOption(val id: String, val label: String, val value: String, val hasData: Boolean)

/** The channels from one source: timing, the receiver, the car, the driver. */
data class TileOptionGroup(val title: String, val options: List<TileOption>)

/**
 * The track screen's tiles on the iPhone (FBBD-262, FBBD-345), as the Android app's TrackTiles: the
 * channels a tile can show, how each reads, and a tile's list; and the layout of a car (one per car,
 * Setup.trackScreensFor) for the live screen and Settings › Track screen.
 */
object TrackTiles {
    const val NO_VALUE = "–"

    private class Channel(val id: String, val label: String, val unit: String)

    private val timingChannels = listOf(
        Channel(TileIds.DELTA, "Delta", "s"),
        Channel(TileIds.LAP_TIME, "Lap time", ""),
        Channel(TileIds.LAST_LAP, "Last lap", ""),
        Channel(TileIds.BEST_LAP, "Best lap", ""),
        Channel(TileIds.PREDICTED_LAP, "Predicted", ""),
        Channel(TileIds.LAP_NUMBER, "Lap", ""),
    )

    private val gnssChannels = listOf(
        Channel(TileIds.SPEED, "Speed", "km/h"),
        Channel(TileIds.G, "G", "g"),
        Channel(TileIds.SATELLITES, "Satellites", ""),
    )

    private val obdChannels = StandardPids.all.map { Channel(TileIds.obd(it.id), it.name, it.unit) }

    /** What the car broadcasts on CAN that no generic PID has (FR-S steering angle, brake). */
    private val canChannels = CanBroadcasts.all.map { it.channel }.distinctBy { it.id }
        .filter { c -> StandardPids.all.none { it.id == c.id } }
        .map { Channel(TileIds.obd(it.id), it.name, if (it.unit == "deg") "°" else it.unit) }

    private val heartRate = Channel(TileIds.HEART_RATE, "Heart rate", "bpm")

    /** The gear, from engine speed and road speed (FBBD-397, UI-309 A): listed with the car, after RPM. */
    private val gear = Channel(TileIds.GEAR, "Gear", "")

    /** The car's channels in a tile's list: the generic PIDs with Gear after RPM, then CAN. */
    private val carChannels: List<Channel> = (obdChannels + canChannels).let { list ->
        val at = list.indexOfFirst { it.id == TileIds.obd("rpm") } + 1
        list.take(at) + gear + list.drop(at)
    }

    private val known: Map<String, Channel> =
        (timingChannels + gnssChannels + carChannels + heartRate).associateBy { it.id }

    private val timingIds = timingChannels.map { it.id }.toSet()

    private fun channel(id: String): Channel = known[id] ?: Channel(
        id,
        id.removePrefix(TileIds.OBD_PREFIX).replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
        "",
    )

    /** The tile for slot [id], null for an empty slot. [live] and [timing] are null outside a session. */
    fun tile(id: String?, live: LiveSnapshot?, timing: LiveTimingState?, receiverHasG: Boolean): ScreenTile? {
        if (id.isNullOrBlank()) return null
        val ch = channel(id)
        val label = if (id == TileIds.LAP_NUMBER && timing?.pointToPoint == true) "Run" else ch.label
        val value = value(ch, live, timing) ?: NO_VALUE
        return ScreenTile(id, label, value, ch.unit, hasData(id, live, timing, receiverHasG))
    }

    /**
     * The tile ids of [carId]'s layout, its main screen or its [second], an empty slot as "": Swift
     * gets a list of strings, where nulls in a list would come as NSNull.
     */
    fun ids(setup: Setup, carId: String?, second: Boolean): List<String> =
        setup.trackScreensFor(carId).tiles(if (second) TrackPage.SECOND else TrackPage.MAIN).map { it.orEmpty() }

    /** Sets one tile of [carId]'s layout; null or "" empties it. */
    fun choose(setup: Setup, carId: String?, second: Boolean, slot: Int, id: String?): Setup =
        setup.chooseTile(if (second) TrackPage.SECOND else TrackPage.MAIN, slot, id, carId)

    /** Puts [carId]'s tiles back to the defaults. */
    fun reset(setup: Setup, carId: String?): Setup = setup.resetTiles(carId)

    private fun value(ch: Channel, live: LiveSnapshot?, timing: LiveTimingState?): String? {
        // The receiver's last speed stays known after the fix is lost; it is not the car's speed now.
        val fix = live?.takeIf { it.gnss == GnssState.Ok }
        return when (val id = ch.id) {
            TileIds.SPEED -> fix?.speedKmh?.roundToInt()?.toString()
            TileIds.G -> live?.gCombined?.let { fixed(it, 2) }
            TileIds.SATELLITES -> fix?.sats?.toString()
            TileIds.DELTA -> timing?.deltaSeconds?.let(::formatDelta)
            TileIds.LAP_TIME -> timing?.lapMillis?.let(::formatLapTime)
            TileIds.LAST_LAP -> timing?.lastMillis?.let(::formatLapTime)
            TileIds.BEST_LAP -> timing?.bestMillis?.let(::formatLapTime)
            TileIds.PREDICTED_LAP -> timing?.predictedMillis?.let(::formatLapTime)
            TileIds.LAP_NUMBER -> timing?.lapNumber?.takeIf { it > 0 }?.toString()
            else -> live?.channels?.get(id)?.let { v -> if (v.isFinite()) tileNumber(v, ch.unit) else null }
        }
    }

    /**
     * Whether [id] can have a value in this session: speed and satellites always, G with a receiver
     * that gives it, timing at a track with lines, car and body channels once they have sent a value.
     */
    private fun hasData(id: String, live: LiveSnapshot?, timing: LiveTimingState?, receiverHasG: Boolean): Boolean =
        when (id) {
            TileIds.SPEED, TileIds.SATELLITES -> true
            TileIds.G -> receiverHasG
            in timingIds -> timing != null
            else -> live?.channels?.containsKey(id) == true
        }

    /**
     * A tile's list (UI-124 A): timing, the receiver's channels, every OBD-II channel and heart rate,
     * each with its value now. [receiver] names the GNSS group; a channel without data says so.
     */
    fun options(
        live: LiveSnapshot?,
        timing: LiveTimingState?,
        receiverHasG: Boolean,
        receiver: String?,
    ): List<TileOptionGroup> {
        fun options(list: List<Channel>) = list.map { ch ->
            val t = tile(ch.id, live, timing, receiverHasG)
            val value = when {
                t == null || !t.hasData -> "No data"
                t.value == NO_VALUE -> NO_VALUE
                t.unit.isEmpty() || t.unit.equals(t.label, ignoreCase = true) -> t.value
                else -> "${t.value} ${t.unit}"
            }
            TileOption(ch.id, ch.label, value, hasData = t?.hasData == true)
        }
        // Car channels the recorder sends that are not generic PIDs, after the generic ones.
        val otherObd = live?.channels?.keys.orEmpty()
            .filter { it.startsWith(TileIds.OBD_PREFIX) && it !in known }
            .sorted()
            .map(::channel)
        return listOf(
            TileOptionGroup("Timing", options(timingChannels)),
            TileOptionGroup(listOfNotNull("GPS", receiver).joinToString(" · "), options(gnssChannels)),
            TileOptionGroup("Car · OBD-II", options(carChannels + otherObd)),
            TileOptionGroup("You · heart rate", options(listOf(heartRate))),
        )
    }

    /** A tile's list in Settings › Track screen (FBBD-345): no session, so no values and none dimmed. */
    fun settingsOptions(receiverHasG: Boolean, receiver: String?): List<TileOptionGroup> =
        options(null, null, receiverHasG, receiver).map { g ->
            g.copy(options = g.options.map { it.copy(value = "", hasData = true) })
        }
}

/** Decimals by unit: volts and air flow to a tenth, lambda to a hundredth, the rest whole. */
private fun tileNumber(v: Double, unit: String): String = fixed(
    v,
    when (unit) {
        "V", "g/s" -> 1
        "λ" -> 2
        else -> 0
    },
)
