package com.flappedear.betterdriver.feature.track

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.component.TrackLabel
import com.flappedear.betterdriver.core.ui.textStyle

/** One channel a tile can show, with its value now ("92 °C"), or what is known about it. */
@Immutable
data class TileChoice(val id: String, val label: String, val value: String, val hasData: Boolean)

/** Channels from one source: timing, the receiver, the car, the driver (UI-124 A). */
@Immutable
data class TileChoiceGroup(val title: String, val choices: List<TileChoice>)

/**
 * A tile's list (UI-124 A): "Empty", then every channel grouped by where it comes from, each with
 * its value now. A channel with no data in this session is dimmed and can still be picked, for a
 * device that connects later. Picking one, or Empty, closes the list.
 */
@Composable
internal fun TilePicker(
    title: String,
    current: String?,
    groups: List<TileChoiceGroup>,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TrackTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(PickerWidthFraction)
                .widthIn(max = PickerWidth)
                .fillMaxHeight(PickerHeightFraction)
                .background(c.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(TrackSlots.Picker),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = textStyle(18.dp), color = c.text)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceRow("Empty", "", selected = current == null, hasData = true) { onChoose(null) }
                groups.forEach { group ->
                    TrackLabel(group.title, Modifier.padding(top = 10.dp, bottom = 2.dp))
                    group.choices.forEach { choice ->
                        ChoiceRow(choice.label, choice.value, choice.id == current, choice.hasData) {
                            onChoose(choice.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, selected: Boolean, hasData: Boolean, onClick: () -> Unit) {
    val c = TrackTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .alpha(if (hasData) 1f else NoDataAlpha),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).border(2.dp, if (selected) c.gain else c.textDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(c.gain, CircleShape))
        }
        Text(
            label,
            style = textStyle(16.dp, if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = textStyle(15.dp, FontWeight.Medium), color = c.textDim, maxLines = 1)
    }
}

private val PickerWidth = 440.dp

/** On a phone in portrait (Settings, FBBD-345) the list is narrower than [PickerWidth]. */
private const val PickerWidthFraction = 0.92f
private const val PickerHeightFraction = 0.92f

/** As the review's mock dims a channel the car does not answer. */
private const val NoDataAlpha = 0.45f
