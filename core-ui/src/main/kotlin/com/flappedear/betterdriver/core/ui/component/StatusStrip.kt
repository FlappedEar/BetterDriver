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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.core.ui.labelStyle
import com.flappedear.betterdriver.core.ui.textStyle

/** Icons a status item can show instead of its dot and label (UI-110 B). */
enum class StatusIcon { Car, Heart }

/**
 * One source in the status strip. [ok] false shows a warning-coloured dot and the value in amber.
 * With an [icon], the icon in the dot's colour replaces the dot and the label; the label is then only
 * read out by TalkBack. While [ok], the quiet strip (UI-141 B) leaves out the value unless
 * [keepValue], as for the heart rate.
 */
@Immutable
data class StatusItem(
    val label: String,
    val value: String,
    val ok: Boolean = true,
    val icon: StatusIcon? = null,
    val keepValue: Boolean = false,
)

/** Material Icons, Round style (UI-19 option B): directions_car and favorite. */
private fun statusIcon(icon: StatusIcon): ImageVector = when (icon) {
    StatusIcon.Car -> CarIcon
    StatusIcon.Heart -> HeartIcon
}

private val CarIcon = vector(
    "Car",
    "M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01l-1.97 5.67c-.07.21-.11.43-.11.66" +
        "v7.16c0 .83.67 1.5 1.5 1.5S6 20.33 6 19.5V19h12v.5c0 .82.67 1.5 1.5 1.5.82 0 1.5-.67 1.5-1.5v-7.16" +
        "c0-.22-.04-.45-.11-.66l-1.97-5.67zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5" +
        "S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z" +
        "M5 11l1.27-3.82c.14-.4.52-.68.95-.68h9.56c.43 0 .81.28.95.68L19 11H5z",
)

private val HeartIcon = vector(
    "Heart",
    "M13.35 20.13c-.76.69-1.93.69-2.69-.01l-.11-.1C5.3 15.27 1.87 12.16 2 8.28c.06-1.7.93-3.33 2.34-4.29" +
        "c2.64-1.8 5.9-.96 7.66 1.1 1.76-2.06 5.02-2.91 7.66-1.1 1.41.96 2.28 2.59 2.34 4.29" +
        ".14 3.88-3.3 6.99-8.55 11.76l-.1.09z",
)

private fun vector(name: String, path: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(path).toNodes(),
    fill = SolidColor(Color.Black),
).build()

/**
 * Status strip (UI-07 option A): GNSS, camera, OBD and heart rate (UI-110 B), and the recording
 * clock on the right. Quiet while fine (UI-141 B): a green dot and a name, with values only for the
 * heart rate, the recording time and anything wrong, which shows in amber; so a value on the strip
 * means look. Replaced by [AlertBanner] when an alert is active.
 * With [pages] above one, dots at the right end, after the clock, show which screen of the track
 * screen is on (UI-122 A): the lit one is [page].
 */
@Composable
fun StatusStrip(
    items: List<StatusItem>,
    recordingTime: String?,
    modifier: Modifier = Modifier,
    page: Int = 0,
    pages: Int = 1,
) {
    val c = TrackTheme.colors
    Row(
        modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = if (item.ok) c.gain else c.warning
                if (item.icon != null) {
                    Icon(
                        statusIcon(item.icon),
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Dot(tint)
                    Text(item.label, style = textStyle(13.dp, FontWeight.Medium), color = c.textDim, maxLines = 1)
                }
                if (!item.ok || item.keepValue) {
                    NumberText(
                        item.value,
                        size = 15.dp,
                        color = if (item.ok) c.text else c.warning,
                        weight = FontWeight.SemiBold,
                    )
                }
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
        if (pages > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(pages) { Dot(if (it == page) c.text else c.textDim.copy(alpha = UnlitDotAlpha)) }
            }
        }
    }
}

/** The other screen's dot: there, but plainly not lit. */
private const val UnlitDotAlpha = 0.35f

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
        // Full colour, as the title: faded, it falls short of 7:1 for sunlight (FBBD-65).
        Text(
            detail,
            style = textStyle(14.dp, FontWeight.Medium),
            color = fg,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (recordingTime != null) {
            NumberText("● $recordingTime", size = 15.dp, color = fg, weight = FontWeight.SemiBold)
        }
    }
}
