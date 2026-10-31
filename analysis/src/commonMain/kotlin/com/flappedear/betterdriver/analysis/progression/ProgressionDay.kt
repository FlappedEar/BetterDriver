package com.flappedear.betterdriver.analysis.progression

// Common Kotlin (Android and iOS): the calendar days and time zone the progression (FBBD-181) groups
// sessions by, in place of java.time's LocalDate, YearMonth and ZoneId, which common Kotlin has not.
// Days and months are written as java.time writes them: ISO "2026-10-12" and "2026-10", and
// "12 Oct 2026", "October 2026" and "12 Oct 2026, 10:15" in English, whatever the phone's language.

private const val MillisPerDay = 86_400_000L
private const val MillisPerMinute = 60_000L
private const val MinutesPerHour = 60
private const val MonthsPerYear = 12
private const val IsoParts = 3
private const val YearDigits = 4
private const val ShortMonth = 3
private const val MaxDayOfMonth = 31

/** A time zone as the progression needs it: its offset from UTC at an instant, both in milliseconds. */
fun interface DayZone {
    fun offsetMillis(utcMillis: Long): Long

    companion object {
        val Utc = DayZone { 0L }

        /** A zone always [offsetMillis] ahead of UTC. */
        fun fixed(offsetMillis: Long) = DayZone { offsetMillis }
    }
}

/** The phone's own time zone. */
expect fun systemDayZone(): DayZone

/** A calendar day, [epochDay] days after 1 January 1970, as java.time's LocalDate.toEpochDay(). */
class ProgressionDay(val epochDay: Long) : Comparable<ProgressionDay> {
    private val civil: Triple<Int, Int, Int> by lazy { civilOf(epochDay) }
    val year: Int get() = civil.first

    /** 1 for January to 12 for December. */
    val month: Int get() = civil.second
    val dayOfMonth: Int get() = civil.third

    fun plusDays(days: Long) = ProgressionDay(epochDay + days)

    fun minusDays(days: Long) = ProgressionDay(epochDay - days)

    fun isBefore(other: ProgressionDay) = epochDay < other.epochDay

    fun isAfter(other: ProgressionDay) = epochDay > other.epochDay

    override fun compareTo(other: ProgressionDay) = epochDay.compareTo(other.epochDay)

    override fun equals(other: Any?) = other is ProgressionDay && other.epochDay == epochDay

    override fun hashCode() = epochDay.hashCode()

    /** "2026-10-12", as LocalDate writes it. */
    override fun toString() = "${monthKey()}-${pad(dayOfMonth)}"

    /** "2026-10", as YearMonth writes it. */
    fun monthKey() = "${year.toString().padStart(YearDigits, '0')}-${pad(month)}"

    companion object {
        fun of(year: Int, month: Int, dayOfMonth: Int): ProgressionDay {
            require(month in 1..MonthsPerYear && dayOfMonth in 1..MaxDayOfMonth) { "bad date $year-$month-$dayOfMonth" }
            val day = ProgressionDay(epochDayOf(year, month, dayOfMonth))
            require(day.month == month && day.dayOfMonth == dayOfMonth) { "bad date $year-$month-$dayOfMonth" }
            return day
        }

        /** The day of ISO "2026-10-12"; throws IllegalArgumentException for anything else. */
        fun parse(iso: String): ProgressionDay {
            val parts = iso.split('-')
            require(parts.size == IsoParts && parts[0].length == YearDigits) { "not an ISO day: $iso" }
            val n = parts.map { requireNotNull(it.toIntOrNull()) { "not an ISO day: $iso" } }
            return of(n[0], n[1], n[2])
        }

        /** The day it is in [zone] at [utcMillis]. */
        fun at(utcMillis: Long, zone: DayZone) =
            ProgressionDay((utcMillis + zone.offsetMillis(utcMillis)).floorDiv(MillisPerDay))
    }
}

/** "12 Oct 2026". */
fun ProgressionDay.dayLabel() = "$dayOfMonth ${MonthNames[month - 1].take(ShortMonth)} $year"

/** "12 Oct", as java.time's "d MMM" in English. */
fun ProgressionDay.dayMonthLabel() = "$dayOfMonth ${MonthNames[month - 1].take(ShortMonth)}"

/** "October 2026". */
fun ProgressionDay.monthLabel() = "${MonthNames[month - 1]} $year"

/** "12 Oct 2026, 10:15": the time [utcMillis] in [zone]. */
fun sessionTimeLabel(utcMillis: Long, zone: DayZone): String {
    val local = utcMillis + zone.offsetMillis(utcMillis)
    val minutes = (local.mod(MillisPerDay) / MillisPerMinute).toInt()
    val day = ProgressionDay(local.floorDiv(MillisPerDay))
    return "${day.dayLabel()}, ${pad(minutes / MinutesPerHour)}:${pad(minutes % MinutesPerHour)}"
}

private val MonthNames = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun pad(n: Int) = n.toString().padStart(2, '0')

// H. Hinnant's days_from_civil and civil_from_days, as java.time counts them.
@Suppress("MagicNumber")
private fun epochDayOf(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val m = month.toLong()
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

@Suppress("MagicNumber")
private fun civilOf(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
    return Triple(y.toInt(), m.toInt(), d.toInt())
}
