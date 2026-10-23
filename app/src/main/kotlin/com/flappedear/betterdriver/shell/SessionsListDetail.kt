package com.flappedear.betterdriver.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.BetterDriverTheme
import com.flappedear.betterdriver.core.ui.layout.HeightClass
import com.flappedear.betterdriver.core.ui.layout.LayoutSize
import com.flappedear.betterdriver.core.ui.layout.WidthClass

/**
 * Sessions as list and detail (FBBD-40): on a tablet the session list sits on the left and the picked
 * session's lap analysis on the right ([detail]), on a phone the list alone. [shown] is the picked
 * session, null when none is; Back closes it ([onClose]). A tablet turned upright with a session open
 * keeps showing it on its own.
 *
 * The list and each session's analysis keep their state (scroll, tab, compared lap) when the tablet
 * turns, as the panes rearrange (FBBD-323).
 *
 * [detail] gets the session, the size class of its pane, and its back action: none beside the list,
 * where the list stays in view.
 */
@Composable
internal fun SessionsListDetail(
    layout: LayoutSize,
    shown: String?,
    onClose: () -> Unit,
    detail: @Composable (id: String, pane: LayoutSize, onBack: (() -> Unit)?) -> Unit,
    list: @Composable (Modifier) -> Unit,
) {
    val states = rememberSaveableStateHolder()
    BackHandler(enabled = shown != null, onBack = onClose)
    val panes = sessionsPanes(layout, shown)
    // Every arrangement composes each pane at the same place: saved state is found by its place, so
    // a pane moved elsewhere when the tablet turns would start over (FBBD-323).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = panes == SessionsPanes.ListAndDetail
        val listWidth = sessionListWidthDp(maxWidth.value)
        val pane = if (sideBySide) layout.pane(maxWidth.value - listWidth) else layout
        Row(Modifier.fillMaxSize()) {
            if (panes != SessionsPanes.Detail) {
                val width = if (sideBySide) Modifier.width(listWidth.dp) else Modifier.weight(1f)
                states.SaveableStateProvider(ListKey) { list(width) }
            }
            if (sideBySide) VerticalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            if (panes != SessionsPanes.List) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (shown == null) {
                        PickSession()
                    } else {
                        states.SaveableStateProvider(detailKey(shown)) {
                            detail(shown, pane, onClose.takeUnless { sideBySide })
                        }
                    }
                }
            }
        }
    }
}

/** The detail pane before a session is picked. */
@Composable
private fun PickSession() {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar("Lap analysis")
        Box(Modifier.padding(horizontal = 16.dp)) {
            ShellCard { DimText("Pick a session on the left to see its lap analysis here.") }
        }
    }
}

private const val ListKey = "list"

/** A session's state key, kept apart from [ListKey] whatever the session id. */
private fun detailKey(id: String) = "detail:$id"

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun SessionsListDetailPreview() {
    BetterDriverTheme(darkTheme = true) {
        SessionsListDetail(
            layout = LayoutSize(WidthClass.Expanded, HeightClass.Medium),
            shown = null,
            onClose = {},
            detail = { _, _, _ -> },
            list = { m -> Column(m) { ShellTopBar("Sessions") } },
        )
    }
}
