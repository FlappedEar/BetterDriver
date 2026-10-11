package com.flappedear.betterdriver.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons from the round 2 mockups. Tinted by Icon, so the fill colour does not matter. */
internal object ShellIcons {
    val Drive = icon(
        "Drive",
        "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 3.5a6.5 6.5 0 0 1 6.3 5H5.7a6.5 6.5 0 0 1 6.3-5z" +
            "M5.6 13h3.9l1.2 5.4A6.5 6.5 0 0 1 5.6 13zm7.7 5.4 1.2-5.4h3.9a6.5 6.5 0 0 1-5.1 5.4z",
    )
    val Sessions = icon("Sessions", "M4 5h16v3H4zm0 5.5h16v3H4zM4 16h16v3H4z")
    val Settings = icon(
        "Settings",
        "M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L15 3h-4l-.4 2.9a7.4 7.4 0 0 0-1.7 1" +
            "l-2.5-1-2 3.5L6.6 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1a7.4 7.4 0 0 0 1.7 1L11 21h4l.4-2.9" +
            "a7.4 7.4 0 0 0 1.7-1l2.5 1 2-3.5zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z",
    )
    val Back = icon("Back", "M15.4 4.6 14 3.2 5.2 12l8.8 8.8 1.4-1.4L8 12z")

    private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}
