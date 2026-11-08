package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.format.fixed
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

/** A widget's or a value plate's top left corner, as shares of the picture's width and height. */
@Serializable
data class HudSpot(val x: Float, val y: Float)

/**
 * A plate of the driver's own (FBBD-472, UI-319 A): any channel the session recorded, by its [channel]
 * name, shown as [label], its value and [unit], with its top left corner at [x], [y] (shares).
 */
@Serializable
data class HudValue(val channel: String, val label: String, val unit: String = "", val x: Float, val y: Float) {
    /** Where the plate goes, as shares of the picture. */
    val share: Box get() = Box(x, y, x + Width, y + Height)

    companion object {
        /** A value plate is heart rate's size. */
        const val Width = 0.1f
        const val Height = 0.16f

        /** At most this many plates (UI-319 A). */
        const val Max = 5
    }
}

/**
 * What the overlay editor (FBBD-472, round 65) chose for the session video: the Tech HUD widgets
 * switched off ([hidden]), the widgets moved from their template places ([places], UI-322 A) and the
 * driver's own value plates ([values], UI-319 A). One choice for every video (UI-316 A).
 *
 * The switched-off set is kept, not the shown one, so a widget added later (the weather plate,
 * FBBD-471) starts switched on. A widget is drawn when the session has data for it ([hudWidgets]) and
 * it is not [hidden].
 */
@Suppress("TooManyFunctions") // One small change per thing the editor does.
class HudChoice(
    hidden: Set<HudWidget> = emptySet(),
    places: Map<HudWidget, HudSpot> = emptyMap(),
    values: List<HudValue> = emptyList(),
) {
    val hidden: Set<HudWidget> = hidden.toSet()
    val places: Map<HudWidget, HudSpot> = places.toMap()
    val values: List<HudValue> = values.toList()

    /** Whether the editor's switch for [widget] is on. */
    fun isShown(widget: HudWidget): Boolean = widget !in hidden

    /** This choice with [widget]'s switch set to [shown]. */
    fun with(widget: HudWidget, shown: Boolean): HudChoice =
        copy(hidden = if (shown) hidden - widget else hidden + widget)

    /** How many of [available] widgets the choice draws: the editor's "10 of 13". */
    fun shownCount(available: Collection<HudWidget> = HudWidget.entries): Int = available.count { isShown(it) }

    /** This choice as [data]'s video draws it: without the plates of channels the session has not. */
    fun drawnOver(data: OverlayData): HudChoice = copy(values = hudValues(data, this))

    /** Whether a widget or plate has left its template place: Reset has something to do. */
    val moved: Boolean
        get() = places.isNotEmpty() || values.withIndex().any { (i, v) -> spotOf(i) != HudSpot(v.x, v.y) }

    /**
     * This choice with [widget]'s top left corner moved to [x], [y] (shares, snapped to the grid and kept
     * inside the picture); unchanged when it would then cover another of the [shown] widgets or a plate.
     */
    fun move(widget: HudWidget, x: Float, y: Float, shown: Set<HudWidget>): HudChoice {
        val spot = snap(x, y, widget.width, widget.height)
        val next = copy(places = places + (widget to spot))
        val others = TechHudShares(shown + widget, next)
        val mine = others.widgets[widget] ?: return this
        val clash = others.widgets.any { (w, b) -> w != widget && b.overlaps(mine) } ||
            others.values.any { it.overlaps(mine) }
        return if (clash) this else next
    }

    /** As [move], for value plate [index]. */
    fun moveValue(index: Int, x: Float, y: Float, shown: Set<HudWidget>): HudChoice {
        val v = values.getOrNull(index) ?: return this
        val spot = snap(x, y, HudValue.Width, HudValue.Height)
        val moved = v.copy(x = spot.x, y = spot.y)
        val rest = values.filterIndexed { i, _ -> i != index }
        val next = values.toMutableList().also { it[index] = moved }
        return if (fits(moved.share, rest, shown)) copy(values = next) else this
    }

    /**
     * This choice with a plate for [channel] added in the first free place along the top row; unchanged
     * when it has one already, has [HudValue.Max] or no place is free of the [shown] widgets.
     */
    fun withValue(channel: String, label: String, unit: String, shown: Set<HudWidget>): HudChoice {
        val full = values.size >= HudValue.Max || values.any { it.channel == channel }
        val spot = if (full) {
            null
        } else {
            freeSpots().firstOrNull { s ->
                fits(Box(s.x, s.y, s.x + HudValue.Width, s.y + HudValue.Height), values, shown)
            }
        }
        val plate = spot?.let { HudValue(channel, label.ifBlank { channel }, unit, it.x, it.y) }
        return plate?.let { copy(values = values + it) } ?: this
    }

    /** This choice without [channel]'s plate. */
    fun withoutValue(channel: String): HudChoice = copy(values = values.filterNot { it.channel == channel })

    /** This choice with [channel]'s plate showing [label]. */
    fun renameValue(channel: String, label: String): HudChoice =
        copy(values = values.map { if (it.channel == channel) it.copy(label = label.ifBlank { channel }) else it })

    /** Every widget back in its Tech HUD place and the plates along the top row (UI-322 A Reset). */
    fun reset(): HudChoice =
        copy(places = emptyMap(), values = values.mapIndexed { i, v -> spotOf(i).let { v.copy(x = it.x, y = it.y) } })

    /** The stored form, as JSON; [decode] reads it back. */
    fun encode(): String = Format.encodeToString(
        Stored.serializer(),
        Stored(
            hidden = HudWidget.entries.filter { it in hidden }.map { it.name },
            places = HudWidget.entries.mapNotNull { w -> places[w]?.let { w.name to it } }.toMap(),
            values = values,
        ),
    )

    override fun equals(other: Any?): Boolean =
        other is HudChoice && other.hidden == hidden && other.places == places && other.values == values

    override fun hashCode(): Int = (hidden.hashCode() * 31 + places.hashCode()) * 31 + values.hashCode()

    override fun toString(): String = "HudChoice(${encode()})"

    private fun copy(
        hidden: Set<HudWidget> = this.hidden,
        places: Map<HudWidget, HudSpot> = this.places,
        values: List<HudValue> = this.values,
    ) = HudChoice(hidden, places, values)

    private fun fits(box: Box, plates: List<HudValue>, shown: Set<HudWidget>): Boolean {
        val shares = TechHudShares(shown, copy(values = plates))
        return shares.widgets.values.none { it.overlaps(box) } && shares.values.none { it.overlaps(box) }
    }

    @Serializable
    private class Stored(
        val hidden: List<String> = emptyList(),
        val places: Map<String, HudSpot> = emptyMap(),
        val values: List<HudValue> = emptyList(),
    )

    companion object {
        /** Every widget on, in its template place: what the video drew before the editor. */
        val All = HudChoice()

        /** The editor's grid: a 1920 by 1080 picture's 24 pixel steps. */
        const val GridX = 80
        const val GridY = 45

        private val Format = Json { ignoreUnknownKeys = true }

        /**
         * The choice stored as [encode] wrote it; null, blank or unreadable is [All]. A widget name this
         * version does not know (removed later, or written by a newer app) is skipped.
         */
        fun decode(stored: String?): HudChoice {
            val s = stored?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Format.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?: return All
            val byName = HudWidget.entries.associateBy { it.name }
            return HudChoice(
                hidden = s.hidden.mapNotNull { byName[it] }.toSet(),
                places = s.places.mapNotNull { (k, v) -> byName[k]?.let { it to v } }.toMap(),
                values = s.values.filter { it.channel.isNotBlank() }.distinctBy { it.channel }.take(HudValue.Max),
            )
        }

        /** Plate [i]'s place along the free top row, between the map and heart rate. */
        internal fun spotOf(i: Int): HudSpot = HudSpot(FirstValueX + i * ValueStepX, TopY)

        /** The places a new plate tries: the top row, then the grid row by row. */
        private fun freeSpots(): Sequence<HudSpot> = sequence {
            for (i in 0 until HudValue.Max) yield(spotOf(i))
            for (gy in 0..((1 - HudValue.Height) * GridY).toInt()) {
                for (gx in 0..((1 - HudValue.Width) * GridX).toInt()) {
                    yield(HudSpot(gx.toFloat() / GridX, gy.toFloat() / GridY))
                }
            }
        }

        private fun snap(x: Float, y: Float, width: Float, height: Float): HudSpot {
            fun one(v: Float, size: Float, steps: Int): Float {
                val snapped = (v * steps).roundToInt().toFloat() / steps
                return snapped.coerceIn(0f, (1 - size).coerceAtLeast(0f))
            }
            return HudSpot(one(x, width, GridX), one(y, height, GridY))
        }

        private const val FirstValueX = 0.19f
        private const val ValueStepX = 0.11f
        private const val TopY = 0.015f
    }
}

/** The widgets drawn over [data]'s video: those it has data for, less those [choice] switched off. */
fun hudWidgets(data: OverlayData, choice: HudChoice): Set<HudWidget> = hudWidgets(data) - choice.hidden

/** The value plates of [choice] that [data] has a channel for: the rest would only show a dash. */
fun hudValues(data: OverlayData, choice: HudChoice): List<HudValue> =
    choice.values.filter { data.hasChannel(it.channel) }

/** A value plate's number: "3.82", "13.9", "142"; a dash without a reading. */
fun hudValueText(value: Double?): String = when {
    value == null || !value.isFinite() -> HudDash
    abs(value) >= Hundred -> value.roundToInt().toString()
    abs(value) >= Ten -> fixed(value, 1)
    else -> fixed(value, 2)
}

private const val Hundred = 100.0
private const val Ten = 10.0

/** The overlay editor's name of [widget] (FBBD-472), the same on Android and iOS. */
fun hudWidgetName(widget: HudWidget): String = when (widget) {
    HudWidget.Tachometer -> "Speed, RPM, gear"
    HudWidget.Lap -> "Lap time, delta, best"
    HudWidget.Pedals -> "Throttle, brake"
    HudWidget.Oil -> "Oil temp"
    HudWidget.Gearbox -> "ATF temp"
    HudWidget.Coolant -> "Coolant temp"
    HudWidget.Tyres -> "Tyres"
    HudWidget.HeartRate -> "Heart rate"
    HudWidget.GRadar -> "G radar"
    HudWidget.GReadout -> "G readout"
    HudWidget.Steering -> "Steering"
    HudWidget.TrackMap -> "Track map"
}

/** The editor row's count, "10 of 13 widgets", plus the value plates: "10 of 13 widgets, 2 values". */
fun hudChoiceSummary(choice: HudChoice): String {
    val n = choice.values.size
    val widgets = "${choice.shownCount()} of ${HudWidget.entries.size} widgets"
    return when (n) {
        0 -> widgets
        1 -> "$widgets, 1 value"
        else -> "$widgets, $n values"
    }
}

/** A value plate's suggested label: the channel's name without its source, upper case. */
fun hudValueLabel(channel: String): String =
    channel.substringBefore('-').removePrefix("obd_").replace('_', ' ').trim().uppercase().take(MaxLabel)

private const val MaxLabel = 10
