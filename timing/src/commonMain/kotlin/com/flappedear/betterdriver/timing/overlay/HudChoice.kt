package com.flappedear.betterdriver.timing.overlay

/**
 * The Tech HUD widgets the driver switched off in the overlay editor (FBBD-472). Kept as the
 * switched-off set, so a widget added later (the weather plate, FBBD-471) starts switched on.
 *
 * A widget is drawn when the session has data for it ([hudWidgets]) and it is not in [hidden].
 */
class HudChoice(hidden: Set<HudWidget> = emptySet()) {
    val hidden: Set<HudWidget> = hidden.toSet()

    /** Whether the editor's switch for [widget] is on. */
    fun isShown(widget: HudWidget): Boolean = widget !in hidden

    /** This choice with [widget]'s switch set to [shown]. */
    fun with(widget: HudWidget, shown: Boolean): HudChoice =
        HudChoice(if (shown) hidden - widget else hidden + widget)

    /** How many of [available] widgets the choice draws: the editor's "10 of 13". */
    fun shownCount(available: Collection<HudWidget> = HudWidget.entries): Int = available.count { isShown(it) }

    /** The stored form: switched-off widget names, comma separated, in the template's order. */
    fun encode(): String = HudWidget.entries.filter { it in hidden }.joinToString(",") { it.name }

    override fun equals(other: Any?): Boolean = other is HudChoice && other.hidden == hidden

    override fun hashCode(): Int = hidden.hashCode()

    override fun toString(): String = "HudChoice(hidden=${encode()})"

    companion object {
        /** Every widget on: what the video drew before the editor. */
        val All = HudChoice()

        /**
         * The choice stored as [encode] wrote it; null or blank is [All]. A name this version does
         * not know (a widget removed later, or written by a newer app) is skipped.
         */
        fun decode(stored: String?): HudChoice {
            if (stored.isNullOrBlank()) return All
            val byName = HudWidget.entries.associateBy { it.name }
            return HudChoice(stored.split(',').mapNotNull { byName[it.trim()] }.toSet())
        }
    }
}

/** The widgets drawn over [data]'s video: those it has data for, less those [choice] switched off. */
fun hudWidgets(data: OverlayData, choice: HudChoice): Set<HudWidget> = hudWidgets(data) - choice.hidden
