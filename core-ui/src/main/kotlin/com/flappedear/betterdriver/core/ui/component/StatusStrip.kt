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
enum class StatusIcon { Track, Gnss, Camera, Car, Heart, Tyre }

/**
 * One source in the status strip. [ok] false shows a warning-coloured dot or icon.
 * With an [icon], the icon in the dot's colour replaces the dot and the label; the label is then only
 * read out by TalkBack. The strip shows the value only with [keepValue], as for the heart rate
 * (Arek 2026-11-03: icons only, no words); the value is still read out.
 */
@Immutable
data class StatusItem(
    val label: String,
    val value: String,
    val ok: Boolean = true,
    val icon: StatusIcon? = null,
    val keepValue: Boolean = false,
)

/**
 * Material Icons, Round style (UI-19 option B): flag, gps_fixed, videocam, directions_car and favorite; the
 * tyre is the car dashboard's tyre pressure symbol drawn in the same round style (FBBD-444).
 */
private fun statusIcon(icon: StatusIcon): ImageVector = when (icon) {
    StatusIcon.Track -> TrackIcon
    StatusIcon.Gnss -> GnssIcon
    StatusIcon.Camera -> CameraIcon
    StatusIcon.Car -> CarIcon
    StatusIcon.Heart -> HeartIcon
    StatusIcon.Tyre -> TyreIcon
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

private val TrackIcon = vector(
    "Track",
    "M14.4 6l-.24-1.2c-.09-.46-.5-.8-.98-.8H6c-.55 0-1 .45-1 1v15c0 .55.45 1 1 1s1-.45 1-1v-6h5.6l.24 1.2" +
        "c.09.47.5.8.98.8H19c.55 0 1-.45 1-1V7c0-.55-.45-1-1-1h-4.6z",
)

private val GnssIcon = vector(
    "GNSS",
    "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V2" +
        "c0-.55-.45-1-1-1s-1 .45-1 1v1.06C6.83 3.52 3.52 6.83 3.06 11H2c-.55 0-1 .45-1 1s.45 1 1 1h1.06" +
        "c.46 4.17 3.77 7.48 7.94 7.94V22c0 .55.45 1 1 1s1-.45 1-1v-1.06c4.17-.46 7.48-3.77 7.94-7.94H22" +
        "c.55 0 1-.45 1-1s-.45-1-1-1h-1.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
)

private val CameraIcon = vector(
    "Camera",
    "M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l2.29 2.29" +
        "c.63.63 1.71.18 1.71-.71V8.91c0-.89-1.08-1.34-1.71-.71L17 10.5z",
)

/** A tyre in section with an exclamation mark and three tread blocks, as on a car's TPMS warning light. */
private val TyreIcon = vector(
    "Tyre",
    "M6.1 3.2c.4-.6 1.3-.5 1.6.1.2.4.1.8-.1 1.2C6.6 6.6 6 9 6 11.6c0 2.5.6 4.8 1.6 6.4h8.8" +
        "c1-1.6 1.6-3.9 1.6-6.4 0-2.6-.6-5-1.6-7.1-.2-.4-.3-.8-.1-1.2.3-.6 1.2-.7 1.6-.1" +
        "C19.2 5.6 20 8.5 20 11.6c0 3.2-.9 6.1-2.4 8.2-.3.4-.7.6-1.2.6H7.6c-.5 0-.9-.2-1.2-.6" +
        "C4.9 17.7 4 14.8 4 11.6c0-3.1.8-6 2.1-8.4z" +
        "M7 21.4c0-.5.4-.9.9-.9h1.2c.5 0 .9.4.9.9s-.4.9-.9.9H7.9c-.5 0-.9-.4-.9-.9z" +
        "m4 0c0-.5.4-.9.9-.9h.2c.5 0 .9.4.9.9s-.4.9-.9.9h-.2c-.5 0-.9-.4-.9-.9z" +
        "m3 0c0-.5.4-.9.9-.9h1.2c.5 0 .9.4.9.9s-.4.9-.9.9h-1.2c-.5 0-.9-.4-.9-.9z" +
        "M12 5c.6 0 1 .4 1 1v5.5c0 .6-.4 1-1 1s-1-.4-1-1V6c0-.6.4-1 1-1z" +
        "m0 9.2c.7 0 1.2.5 1.2 1.2s-.5 1.2-1.2 1.2-1.2-.5-1.2-1.2.5-1.2 1.2-1.2z",
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
 * Status strip (UI-07 option A): GNSS, camera, OBD, heart rate (UI-110 B) and tyres (FBBD-444), and the recording
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
                // Only the heart rate keeps its value (Arek 2026-11-03): the rest are icons, amber when wrong.
                if (item.keepValue) {
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
