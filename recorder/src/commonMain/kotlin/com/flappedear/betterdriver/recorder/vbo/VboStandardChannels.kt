package com.flappedear.betterdriver.recorder.vbo

/**
 * Header names of the standard Racelogic channels this app writes (VboWriter.STANDARD_CHANNELS),
 * in common code so that the VBO import reads them on iOS too. They get no [channel units] line.
 */
val VboStandardChannels: Set<String> = setOf(
    "satellites", "time", "latitude", "longitude", "velocity kmh", "heading", "height",
    "avifileindex", "avisynctime",
)
