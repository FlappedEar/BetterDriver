package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.core.ui.TrackTheme

@Composable
internal fun StatusDot(state: DeviceState, modifier: Modifier = Modifier) {
    val color = when (state) {
        DeviceState.Ready -> TrackTheme.colors.gain
        DeviceState.Attention -> TrackTheme.colors.warning
        DeviceState.Off -> MaterialTheme.colorScheme.outline
    }
    Box(modifier.size(10.dp).background(color, CircleShape))
}

/** Screen title row, with an optional back button. */
@Composable
internal fun ShellTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (onBack == null) 20.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(ShellIcons.Back, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * One group on a shell screen. A plain list on the black background, without a card behind it
 * (UI-143 B): calmer, and it lights fewer pixels. Its rows bring their own thin dividers.
 */
@Composable
internal fun ShellCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Small uppercase caption above a card or a group. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One row of a settings list: a label with an optional [supporting] line under it, an optional
 * value on the right and an optional trailing control. Rows after the first get a divider above them.
 */
@Composable
internal fun ListRow(
    label: String,
    value: String? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (supporting == null) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    DimText(supporting)
                }
            }
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onClick != null) {
                    Icon(
                        ShellIcons.Chevron,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing()
        }
    }
}

/** Secondary text in the dim colour. */
@Composable
internal fun DimText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
    )
}

/** "+ Add …" as the last row of a list card, in the accent colour (UI-33, UI-35). */
@Composable
internal fun AddRow(text: String, onClick: () -> Unit, divider: Boolean = true) {
    Column {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            "+ $text",
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
