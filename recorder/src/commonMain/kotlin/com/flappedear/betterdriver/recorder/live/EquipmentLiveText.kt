package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.core.ble.DiagHeaderText
import com.flappedear.betterdriver.core.ble.DiagRow
import com.flappedear.betterdriver.core.ble.DiagSection
import com.flappedear.betterdriver.format.Minus
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.ChannelDef

/**
 * The text of an Equipment page's live data (FBBD-440), the same on Android and the iPhone, in the
 * RaceBox diagnostics page's look (UI-39 A): a header card, then every channel the device sends in
 * groups, how often it sends, and what its link did. Only what the device sent appears.
 */
object EquipmentLiveText {
    const val TITLE = "Live data"

    /** Under the header while a session records: the session holds the device. */
    const val RECORDING = "Recording. The session is using this device."
    const val RECORDING_NOTE = "Live data shows again when the session ends."

    /** Said once at the bottom of the page. */
    const val NOTE = "Not recorded. The page connects to the device only while it is open and no session " +
        "records; starting a session hands the device back."

    /** After this long without a value the header turns amber. */
    const val SILENT_MS = 5_000L

    private const val MsPerSecond = 1_000L
    private const val SecondsPerMinute = 60L
    private const val RateDecimalsBelow = 10.0
    private const val MaxEventText = 80

    fun header(name: String, s: EquipmentLiveSnapshot): DiagHeaderText {
        val since = s.sinceLastMs
        return when {
            since == null -> DiagHeaderText(false, "Connecting…", detailWithEvent(name, s))
            since > SILENT_MS -> DiagHeaderText(false, "No data for ${since / MsPerSecond} s", detailWithEvent(name, s))
            else -> {
                val n = s.channels.size
                val values = if (n == 1) "value" else "values"
                DiagHeaderText(true, "Receiving · ${rate(s.rateHz)}", "$name · $n $values")
            }
        }
    }

    /** [utcClock] formats a receiver's UTC time of day (epoch milliseconds) as the platform does. */
    fun sections(s: EquipmentLiveSnapshot, utcClock: (Long) -> String): List<DiagSection> = buildList {
        s.channels.groupBy { group(it.def.source) }.entries.sortedBy { GroupOrder.indexOf(it.key) }
            .forEach { (title, channels) ->
                add(DiagSection(title, channels.map { DiagRow(label(it.def), value(it, utcClock)) }))
            }
        add(
            DiagSection(
                "Link",
                buildList {
                    add(DiagRow("Updates", if (s.sets == 0L) "None yet" else rate(s.rateHz)))
                    add(DiagRow("Updates received", s.sets.toString()))
                    s.sinceLastMs?.let { add(DiagRow("Last update", ago(it))) }
                    add(DiagRow("Page open for", duration(s.openMs)))
                },
            ),
        )
        if (s.events.isNotEmpty()) {
            add(DiagSection("Events", s.events.reversed().map { DiagRow(eventText(it), duration(it.atMs)) }))
        }
    }

    /** "Position", "Receiver", "Heart rate", "Tyres", "Phone". */
    private fun group(source: String): String = when (source) {
        "gnss" -> "Position"
        "heart_rate" -> "Heart rate"
        "tpms" -> "Tyres"
        "phone" -> "Phone"
        else -> "Receiver"
    }

    private val GroupOrder = listOf("Position", "Receiver", "Phone", "Heart rate", "Tyres")

    private val Labels = mapOf(
        "gnss_utc_ms" to "Time (UTC)",
        "gnss_lat" to "Latitude",
        "gnss_lon" to "Longitude",
        "gnss_speed" to "Speed",
        "gnss_heading" to "Heading",
        "gnss_alt" to "Altitude",
        "gnss_sats" to "Satellites",
        "gnss_hacc" to "Horizontal accuracy",
        "heart_rate" to "Heart rate",
    )

    private val Prefixes = listOf(
        "gnss_", "nmea_", "diy_", "lns_", "vbox_", "dragy_", "hr_", "rc_", "phone_", "tyre_", "racebox_",
    )
    private val Upper = setOf("hdop", "pdop", "vdop", "dgps", "gps", "fl", "fr", "rl", "rr", "x", "y", "z", "utc")
    private val Words = mapOf("hacc" to "horizontal accuracy", "acc" to "accuracy", "temp" to "temperature")

    /** A channel's name for people: "Latitude", "HDOP", "Pressure FL", "RR interval", "Accel X". */
    fun label(def: ChannelDef): String {
        Labels[def.name]?.let { return it }
        val bare = Prefixes.firstOrNull { def.name.startsWith(it) && def.name.length > it.length }
            ?.let { def.name.removePrefix(it) } ?: def.name
        val words = bare.split('_').filter { it.isNotEmpty() }.map { w ->
            when (w) {
                in Upper -> w.uppercase()
                else -> Words[w] ?: w
            }
        }
        val text = words.joinToString(" ")
        return text.replaceFirstChar { it.uppercase() }.ifEmpty { def.name }
    }

    private fun value(c: LiveChannel, utcClock: (Long) -> String): String {
        if (c.def.name.endsWith("_utc_ms")) return utcClock(c.value.toLong())
        val number = fixed(c.value, c.def.decimals).let { if (it.startsWith("-")) Minus + it.drop(1) else it }
        val unit = when (c.def.unit) {
            "deg" -> "°"
            "deg/s" -> " °/s"
            "" -> ""
            else -> " ${c.def.unit}"
        }
        return number + unit
    }

    private fun rate(hz: Double): String = if (hz < RateDecimalsBelow) "${fixed(hz, 1)} Hz" else "${fixed(hz, 0)} Hz"

    private fun ago(ms: Long): String = "${fixed(ms / MsPerSecond.toDouble(), 1)} s ago"

    /** "12 s", "3 min 05 s". */
    fun duration(ms: Long): String {
        val s = ms / MsPerSecond
        if (s < SecondsPerMinute) return "$s s"
        val sec = (s % SecondsPerMinute).toString().padStart(2, '0')
        return "${s / SecondsPerMinute} min $sec s"
    }

    private fun eventText(e: LiveEvent): String {
        val text = if (e.text.isBlank()) e.kind else "${e.kind.replace('_', ' ')}: ${e.text}"
        return if (text.length > MaxEventText) text.take(MaxEventText - 1) + "…" else text
    }

    private fun detailWithEvent(name: String, s: EquipmentLiveSnapshot): String =
        s.events.lastOrNull()?.let { "$name · ${eventText(it)}" } ?: name
}
