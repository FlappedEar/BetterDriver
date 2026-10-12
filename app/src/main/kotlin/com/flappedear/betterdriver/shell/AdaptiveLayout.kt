package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.core.ui.layout.HeightClass
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass

/** How the app moves between Drive, Sessions and Settings (FBBD-40). */
enum class AppNavigation { None, Bar, Rail }

/**
 * The bottom bar on a phone, a navigation rail on a tablet, and neither away from the three top-level
 * screens: the track screen keeps the whole window.
 */
fun appNavigation(size: LayoutSize, topLevel: Boolean): AppNavigation = when {
    !topLevel -> AppNavigation.None
    size.tablet -> AppNavigation.Rail
    else -> AppNavigation.Bar
}

/** What Sessions shows (FBBD-40). */
enum class SessionsPanes {
    /** The list only: a phone, or nothing picked on a narrow tablet. */
    List,

    /** The picked session's analysis on its own: a tablet turned upright while one was open. */
    Detail,

    /** The list beside the picked session's analysis, or beside a hint to pick one. */
    ListAndDetail,
}

/**
 * The panes of Sessions: two on a tablet, else the picked session's analysis when one is picked and
 * still listed (it stays picked when the tablet turns), else the list.
 */
fun sessionsPanes(size: LayoutSize, shown: String?): SessionsPanes = when {
    size.tablet -> SessionsPanes.ListAndDetail
    shown != null -> SessionsPanes.Detail
    else -> SessionsPanes.List
}

/** The picked session [selected] when it is still in [listed]; a deleted session is no longer shown. */
fun shownSession(selected: String?, listed: Collection<String>): String? = selected?.takeIf { it in listed }

/**
 * The width of the session list beside the analysis, out of [availableDp]: two fifths, at least wide
 * enough for a session card's buttons and no wider than a phone, so the analysis gets the rest.
 */
fun sessionListWidthDp(availableDp: Float): Float =
    (availableDp * ListShare).coerceIn(MinListWidthDp, MaxListWidthDp)

/**
 * Columns of cue cards in the coach review: one on a phone, two on an upright tablet or a large phone
 * held sideways, three on a tablet held sideways.
 */
fun cueColumns(size: LayoutSize): Int = when (size.width) {
    WidthClass.Compact -> 1
    WidthClass.Medium -> if (size.height == HeightClass.Compact) 1 else 2
    WidthClass.Expanded -> if (size.height == HeightClass.Compact) 2 else MostCueColumns
}

private const val MostCueColumns = 3
private const val ListShare = 0.4f
private const val MinListWidthDp = 320f
private const val MaxListWidthDp = 412f
