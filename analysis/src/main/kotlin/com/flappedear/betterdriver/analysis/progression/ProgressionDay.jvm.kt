package com.flappedear.betterdriver.analysis.progression

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val MillisPerSecond = 1000L

/** [this] zone as the progression reads it (FBBD-181). */
fun ZoneId.dayZone(): DayZone = DayZone { rules.getOffset(Instant.ofEpochMilli(it)).totalSeconds * MillisPerSecond }

actual fun systemDayZone(): DayZone = ZoneId.systemDefault().dayZone()

/** [this] day as the progression keeps it. */
fun LocalDate.progressionDay() = ProgressionDay(toEpochDay())
