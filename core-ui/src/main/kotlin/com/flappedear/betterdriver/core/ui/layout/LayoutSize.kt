package com.flappedear.betterdriver.core.ui.layout

/**
 * Width classes with the Material 3 breakpoints: phones held upright, then tablets upright and phones
 * held sideways, then tablets held sideways.
 */
enum class WidthClass {
    Compact,
    Medium,
    Expanded,
    ;

    companion object {
        /** The class of a window or pane [dp] wide. */
        fun of(dp: Float): WidthClass = when {
            dp < MediumWidthDp -> Compact
            dp < ExpandedWidthDp -> Medium
            else -> Expanded
        }
    }
}

/** Height classes with the Material 3 breakpoints; a phone held sideways is Compact. */
enum class HeightClass {
    Compact,
    Medium,
    Expanded,
    ;

    companion object {
        /** The class of a window or pane [dp] tall. */
        fun of(dp: Float): HeightClass = when {
            dp < MediumHeightDp -> Compact
            dp < ExpandedHeightDp -> Medium
            else -> Expanded
        }
    }
}

/**
 * The size class of the window or of the pane a screen is shown in (FBBD-40). The app shell works it
 * out once and passes it down, so screens choose their layout from it instead of measuring themselves.
 */
data class LayoutSize(val width: WidthClass, val height: HeightClass) {
    /**
     * The tablet layout: a navigation rail and two panes. Needs an Expanded width and a window that is not
     * Compact in height, so a phone held sideways (wide but short) keeps the phone layout.
     */
    val tablet: Boolean get() = width == WidthClass.Expanded && height != HeightClass.Compact

    /** Room for two things side by side: Expanded, or Medium unless it is a phone held sideways. */
    val sideBySide: Boolean
        get() = width == WidthClass.Expanded || (width == WidthClass.Medium && height != HeightClass.Compact)

    /** The same height in a pane [dp] wide. */
    fun pane(dp: Float): LayoutSize = copy(width = WidthClass.of(dp))

    companion object {
        /** A phone held upright, the layout every screen falls back to (previews, no activity). */
        val Phone = LayoutSize(WidthClass.Compact, HeightClass.Medium)
    }
}

private const val MediumWidthDp = 600f
private const val ExpandedWidthDp = 840f
private const val MediumHeightDp = 480f
private const val ExpandedHeightDp = 900f
