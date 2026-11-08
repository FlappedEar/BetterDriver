package com.flappedear.betterdriver.timing.overlay

import com.flappedear.betterdriver.recorder.ChannelDef

/** A widget's switch in the overlay editor: on, and whether the session has data for it (UI-317 A). */
class OverlayEditorRow(val widget: HudWidget, val name: String, val on: Boolean, val hasData: Boolean)

/**
 * A plate on the editor's picture, in shares of the picture: a widget ([widget]) or value plate
 * [valueIndex]. [key] names it for dragging and for the UI tests: "w-Oil", "v-obd_oil_pressure".
 */
class OverlayEditorPlate(
    val key: String,
    val label: String,
    val box: Box,
    val widget: HudWidget?,
    val valueIndex: Int,
)

/** A channel the session recorded, offered as a value plate. */
class OverlayChannel(val name: String, val unit: String, val source: String)

/**
 * The overlay editor (FBBD-472, round 65) as the iOS screen shows it, over a [HudChoice] stored as
 * [stored]: flat lists for the switches ([rows]), the picture ([plates]) and the plates' channels, and
 * one call per thing the driver does. With a session ([available], the widgets it has data for, and
 * [channels], what it recorded) the widgets without data are dimmed and values can be added; from
 * Settings there is none. Android's editor works on [HudChoice] directly, with the same rules.
 */
class OverlayEditorState(
    stored: String?,
    available: Set<HudWidget>? = null,
    private val channels: List<ChannelDef>? = null,
) {
    /** The choice as it stands. */
    var choice: HudChoice = HudChoice.decode(stored)
        private set

    /** Opened from a session's Video tab, with its data. */
    val hasSession: Boolean = available != null

    private val has: Set<HudWidget> = available ?: HudWidget.entries.toSet()

    private val shown: Set<HudWidget> get() = has - choice.hidden

    /** The stored form to save after every change. */
    val stored: String get() = choice.encode()

    /** "10 of 13 widgets, 2 values". */
    val summary: String get() = hudChoiceSummary(choice)

    val rows: List<OverlayEditorRow>
        get() = HudWidget.entries.map { OverlayEditorRow(it, hudWidgetName(it), choice.isShown(it), it in has) }

    val plates: List<OverlayEditorPlate>
        get() {
            val shares = TechHudShares(shown, choice)
            val widgets = shares.widgets.map { (w, b) -> OverlayEditorPlate("w-${w.name}", hudWidgetName(w), b, w, -1) }
            val values = choice.values.mapIndexed { i, v ->
                OverlayEditorPlate("v-${v.channel}", v.label, shares.values[i], null, i)
            }
            return widgets + values
        }

    val values: List<HudValue> get() = choice.values

    /** The session's channels not on a plate yet. */
    val addable: List<OverlayChannel>
        get() {
            val taken = choice.values.map { it.channel }.toSet()
            return channels.orEmpty().filter { it.name !in taken }.map { OverlayChannel(it.name, it.unit, it.source) }
        }

    /** A value can be added: a session is open and fewer than five plates are on. */
    val canAdd: Boolean get() = hasSession && choice.values.size < HudValue.Max

    /** Reset has something to put back. */
    val canReset: Boolean get() = choice.moved

    fun toggle(widget: HudWidget, on: Boolean) {
        choice = choice.with(widget, on, has)
    }

    /** Plate [key] dropped with its top left corner at [x], [y] (shares); stays put if it does not fit. */
    fun drop(key: String, x: Float, y: Float) {
        val plate = plates.firstOrNull { it.key == key } ?: return
        choice = plate.widget?.let { choice.move(it, x, y, shown) } ?: choice.moveValue(plate.valueIndex, x, y, shown)
    }

    fun add(channel: String) {
        val c = addable.firstOrNull { it.name == channel } ?: return
        choice = choice.withValue(c.name, hudValueLabel(c.name), c.unit, shown)
    }

    fun remove(channel: String) {
        choice = choice.withoutValue(channel)
    }

    fun rename(channel: String, label: String) {
        choice = choice.renameValue(channel, label.trim().uppercase())
    }

    fun reset() {
        choice = choice.reset()
    }
}
