package com.flappedear.betterdriver.videoexport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.shell.DimText
import com.flappedear.betterdriver.shell.ListRow
import com.flappedear.betterdriver.shell.ShellCard
import com.flappedear.betterdriver.shell.ShellTopBar
import com.flappedear.betterdriver.timing.overlay.Box as ShareBox
import com.flappedear.betterdriver.timing.overlay.HudChoice
import com.flappedear.betterdriver.timing.overlay.HudValue
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.TechHudShares
import com.flappedear.betterdriver.timing.overlay.hudChoiceSummary
import com.flappedear.betterdriver.timing.overlay.hudValueLabel
import com.flappedear.betterdriver.timing.overlay.hudWidgetName
import com.flappedear.betterdriver.timing.overlay.hudWidgets
import kotlin.math.roundToInt

/**
 * The session video's overlay editor (FBBD-472, round 65): a picture of the Tech HUD on top where
 * each widget and value plate is dragged to its place (UI-322 A, snapped to the grid, never over
 * another), a switch per widget below it (UI-315 A), Reset, and the value plates (UI-319 A). One
 * choice for every video (UI-316 A), opened from a session's Video tab with that session's [data]
 * or from Settings › Video without (UI-314 C). With [data], a widget the session has no data for is
 * listed dimmed (UI-317 A) and the session's channels can be added as plates.
 */
@Composable
fun OverlayEditorScreen(
    choice: HudChoice,
    data: OverlayData?,
    onChange: (HudChoice) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    // The widgets this video has data for; from Settings, every widget.
    val available = remember(data) { data?.let { hudWidgets(it) } ?: HudWidget.entries.toSet() }
    val shown = available - choice.hidden
    var adding by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().testTag(OverlayEditorTag)) {
        ShellTopBar("Overlay", onBack = onBack)
        OverlayPicture(choice, shown, onChange, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DimText("Drag a widget on the picture to move it. Save video and Watch draw what is on here.")
            WidgetSwitches(choice, available, loading, onChange)
            ShellCard {
                ValueRows(choice, onChange, onRename = { renaming = it })
                AddValueRow(data, loading, divider = choice.values.isNotEmpty()) { adding = true }
            }
            ShellCard {
                Box(Modifier.alpha(if (choice.moved) 1f else DimAlpha).testTag(OverlayResetTag)) {
                    ListRow(
                        "Reset to Tech HUD",
                        divider = false,
                        onClick = { if (choice.moved) onChange(choice.reset()) },
                        supporting = "Every widget back in its place",
                    )
                }
            }
        }
    }
    if (adding && data != null) {
        AddValueDialog(data, choice, onAdd = { name, unit ->
            onChange(choice.withValue(name, hudValueLabel(name), unit, shown))
            adding = false
        }, onClose = { adding = false })
    }
    renaming?.let { channel ->
        val v = choice.values.firstOrNull { it.channel == channel }
        if (v == null) {
            renaming = null
        } else {
            RenameDialog(v.label, onDone = { label ->
                onChange(choice.renameValue(channel, label.uppercase()))
                renaming = null
            }, onClose = { renaming = null })
        }
    }
}

/** A switch per widget (UI-315 A); one the session has no data for is dimmed (UI-317 A). */
@Composable
private fun WidgetSwitches(
    choice: HudChoice,
    available: Set<HudWidget>,
    loading: Boolean,
    onChange: (HudChoice) -> Unit,
) {
    ShellCard {
        HudWidget.entries.forEachIndexed { i, w ->
            val has = w in available
            Box(Modifier.alpha(if (has) 1f else DimAlpha).testTag("$OverlaySwitchTag-${w.name}")) {
                ListRow(
                    hudWidgetName(w),
                    divider = i > 0,
                    supporting = if (has || loading) null else "No data in this session",
                ) {
                    Switch(checked = choice.isShown(w), onCheckedChange = { onChange(choice.with(w, it)) })
                }
            }
        }
    }
}

/** Add a value: from the session's channels, or where to find them when opened from Settings. */
@Composable
private fun AddValueRow(data: OverlayData?, loading: Boolean, divider: Boolean, onAdd: () -> Unit) {
    when {
        data != null -> ListRow(
            "Add a value",
            divider = divider,
            onClick = onAdd,
            supporting = "From the channels this session recorded",
        )
        loading -> ListRow("Reading this session's channels…", divider = divider)
        else -> ListRow(
            "Add a value",
            divider = divider,
            supporting = "From a session's Video tab: values come from its recorded channels",
        )
    }
}

/** The value plates: tap to rename, Remove to take one off. */
@Composable
private fun ValueRows(choice: HudChoice, onChange: (HudChoice) -> Unit, onRename: (String) -> Unit) {
    choice.values.forEachIndexed { i, v ->
        ListRow(v.label, divider = i > 0, onClick = { onRename(v.channel) }, supporting = v.channel) {
            TextButton(onClick = { onChange(choice.withoutValue(v.channel)) }) { Text("Remove") }
        }
    }
}

/** The 16:9 picture with the shown widgets and plates at their places, each dragged to move. */
@Composable
private fun OverlayPicture(
    choice: HudChoice,
    shown: Set<HudWidget>,
    onChange: (HudChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shares = remember(choice, shown) { TechHudShares(shown, choice) }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(PictureAspect)
            .background(Brush.verticalGradient(listOf(Sky, Road)), MaterialTheme.shapes.small)
            .testTag(OverlayPictureTag),
    ) {
        val pw = constraints.maxWidth.toFloat()
        val ph = constraints.maxHeight.toFloat()
        for ((w, b) in shares.widgets) {
            Plate("w-${w.name}", hudWidgetName(w), b, pw, ph) { x, y -> onChange(choice.move(w, x, y, shown)) }
        }
        choice.values.forEachIndexed { i, v ->
            Plate("v-${v.channel}", v.label, shares.values[i], pw, ph) { x, y ->
                onChange(choice.moveValue(i, x, y, shown))
            }
        }
    }
}

/** One plate of the picture; dropped, it reports its new top left corner in shares. */
@Composable
private fun Plate(key: String, label: String, b: ShareBox, pw: Float, ph: Float, onDrop: (Float, Float) -> Unit) {
    var drag by remember(key, b) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val dragging = drag != Offset.Zero
    Box(
        Modifier
            .offset { IntOffset((b.left * pw + drag.x).roundToInt(), (b.top * ph + drag.y).roundToInt()) }
            .size(with(density) { (b.width * pw).toDp() }, with(density) { (b.height * ph).toDp() })
            .background(if (dragging) PlateDragging else PlateColor)
            .border(1.dp, Amber.copy(alpha = if (dragging) 1f else BorderAlpha))
            .pointerInput(key, b) {
                detectDragGestures(
                    onDragEnd = {
                        val d = drag
                        drag = Offset.Zero
                        onDrop(b.left + d.x / pw, b.top + d.y / ph)
                    },
                    onDragCancel = { drag = Offset.Zero },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            }
            .testTag("$OverlayPlateTag-$key"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = PlateText,
            lineHeight = PlateText,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(1.dp),
        )
    }
}

/** The session's recorded channels to add as plates; those already on are left out. */
@Composable
private fun AddValueDialog(data: OverlayData, choice: HudChoice, onAdd: (String, String) -> Unit, onClose: () -> Unit) {
    val taken = choice.values.map { it.channel }.toSet()
    val channels = data.recordedChannels.filter { it.name !in taken }
    val full = choice.values.size >= HudValue.Max
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add a value") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    full -> DimText("Five values is the most: remove one first.")
                    channels.isEmpty() -> DimText("Every channel this session recorded is on already.")
                    else -> channels.forEachIndexed { i, c ->
                        ListRow(
                            c.name,
                            divider = i > 0,
                            supporting = listOf(c.source, c.unit).filter { it.isNotBlank() }.joinToString(" · "),
                            onClick = { onAdd(c.name, c.unit) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun RenameDialog(label: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(label) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Plate label") },
        text = { OutlinedTextField(text, { text = it.take(MaxLabel) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** A row that opens the editor, with how many widgets and values it draws (UI-314 C). */
@Composable
fun OverlayRow(choice: HudChoice, onOpen: () -> Unit, divider: Boolean = true) {
    Box(Modifier.fillMaxWidth().testTag(OverlayRowTag)) {
        ListRow("Overlay", hudChoiceSummary(choice), divider = divider, onClick = onOpen)
    }
}

const val OverlayEditorTag = "overlay-editor"
const val OverlayPictureTag = "overlay-picture"
const val OverlaySwitchTag = "overlay-switch"
const val OverlayPlateTag = "overlay-plate"
const val OverlayResetTag = "overlay-reset"
const val OverlayRowTag = "overlay-row"

private const val PictureAspect = 16f / 9f
private const val DimAlpha = 0.45f
private const val BorderAlpha = 0.5f
private const val MaxLabel = 10
private val PlateText = 8.sp
private val Sky = Color(0xFF5D7690)
private val Road = Color(0xFF2A2C2E)
private val PlateColor = Color(0xD10A0C0E)
private val PlateDragging = Color(0xF00A0C0E)
private val Amber = Color(0xFFFFC044)
