package com.flappedear.betterdriver.analysis.progression

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

private const val MillisPerSecond = 1000L

/** The iPhone's time zone as set now, with its summer time: as Android's ZoneId.systemDefault(). */
actual fun systemDayZone(): DayZone {
    val zone = NSTimeZone.localTimeZone
    return DayZone { utc ->
        zone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(utc.toDouble() / MillisPerSecond)) *
            MillisPerSecond
    }
}
