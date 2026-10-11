package com.flappedear.betterdriver.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.labelStyle
import com.flappedear.betterdriver.core.ui.textStyle

/** One source in the status strip. [ok] false shows a warning-coloured dot. */
@Immutable
data class StatusItem(val label: String, val value: String, val ok: Boolean = true)

/**
 * Status strip (UI-07 option A): GNSS, camera, phone heat, battery, and the
 * recording clock on the right. Replaced by [AlertBanner] when an alert is active.
 */
@Composable
fun StatusStrip(
    items: List<StatusItem>,
    recordingTime: String?,
    modifier: Modifier = Modifier,
) {
    val c = TrackTheme.colors
    Row(
        modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (item.ok) c.gain else c.warning)
                Text(item.label, style = textStyle(13.dp, FontWeight.Medium), color = c.textDim, maxLines = 1)
                NumberText(item.value, size = 15.dp, color = c.text, weight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        if (recordingTime != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(c.loss)
                Text("REC", style = labelStyle(13.dp), color = c.textDim)
                NumberText(recordingTime, size = 15.dp, color = c.text, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(9.dp).background(color, CircleShape))
}

enum class AlertLevel { Warning, Critical }

/**
 * Non-blocking alert (UI-08). Takes the status strip's slot, never covers the
 * delta and never needs a tap. Shows the recording clock so the driver can
 * still see that recording continues.
 */
@Composable
fun AlertBanner(
    title: String,
    detail: String,
    level: AlertLevel,
    recordingTime: String?,
    modifier: Modifier = Modifier,
) {
    val c = TrackTheme.colors
    val (bg, fg) = when (level) {
        AlertLevel.Critical -> c.loss to c.onLoss
        AlertLevel.Warning -> c.warning to c.onWarning
    }
    Row(
        modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = textStyle(15.dp), color = fg, maxLines = 1)
        Text(
            detail,
            style = textStyle(14.dp, FontWeight.Medium),
            color = fg.copy(alpha = 0.85f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (recordingTime != null) {
            NumberText("● $recordingTime", size = 15.dp, color = fg, weight = FontWeight.SemiBold)
        }
    }
}
