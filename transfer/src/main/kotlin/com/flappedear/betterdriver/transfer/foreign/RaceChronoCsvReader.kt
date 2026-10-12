package com.flappedear.betterdriver.transfer.foreign

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import java.io.Reader

/**
 * Reads RaceChrono's CSV v3 export (FBBD-164): a few "key,value" header rows ("This file is created
 * using RaceChrono…", "Format,3", "Session title,…", "Created,…"), a blank line, the column names
 * row, then rows of units and channel sources, then the data. The columns row is the first with
 * Timestamp, Latitude and Longitude; the row after it gives the units; any row whose timestamp is
 * not a number (a sources row) is passed over, except that the sources row ("100: gps", "101: acc",
 * "200: obd") gives the mapped and extra channels their provenance (FBBD-174).
 *
 * Mapped: Timestamp (Unix seconds), Latitude, Longitude, Speed (m/s, km/h or mph by its unit),
 * Bearing, Altitude, Satellites, Longitudinal and Lateral acc (G, or m/s² by its unit). A name that
 * appears twice (the same channel from two sources) is taken from its first column. Other numeric
 * columns become extra channels; RaceChrono's own bookkeeping (lap, fragment, elapsed time,
 * distance) is left out, since the app times laps itself. Rows without a position (another
 * channel's samples) are passed over: a session's values are carried on its positions.
 */
internal object RaceChronoCsvReader {

    fun read(input: Reader, sink: ImportSink) {
        val lines = LineReader(input)
        val parse = Parse(sink)
        while (true) {
            val line = lines.next() ?: break
            if (line.isNotBlank()) parse.row(splitCsv(line))
        }
        if (parse.columns == null) {
            throw ImportFormatException("no RaceChrono columns row (Timestamp, Latitude, Longitude)")
        }
    }

    private class Parse(private val sink: ImportSink) {
        var columns: Columns? = null
            private set
        private var unitsPending = false
        private var sourcesPending = false

        fun row(cells: List<String>) {
            val c = columns
            when {
                c == null -> if (isHeader(cells)) {
                    columns = Columns(cells.map { it.trim() })
                    unitsPending = true
                } else {
                    maker(cells.joinToString(","))?.let(sink::receiver)
                }
                cells.getOrNull(c.timestamp)?.trim()?.toDoubleOrNull() == null -> {
                    // Units right after the names; sources and other description rows after that.
                    when {
                        unitsPending -> c.units(cells.map { it.trim() })
                        sourcesPending -> c.sources(cells.map { it.trim() }, sink)
                    }
                    sourcesPending = unitsPending
                    unitsPending = false
                }
                else -> data(c, cells)
            }
        }

        private fun data(c: Columns, cells: List<String>) {
            if (unitsPending) {
                unitsPending = false
                c.units(emptyList())
            }
            sourcesPending = false
            if (!c.announced) {
                c.announced = true
                sink.extras(c.extras)
            }
            // A row without a position holds only another channel's sample (OBD, say) and is passed over.
            c.point(cells)?.let(sink::point)
        }
    }

    /**
     * The app that wrote the file, from the header row "This file is created using RaceChrono v9.0.6
     * ( http://www.racechrono.com/ )." as "RaceChrono v9.0.6" (FBBD-174); null for any other row.
     */
    internal fun maker(line: String): String? {
        val text = line.trim()
        if (!text.startsWith(MakerPrefix, ignoreCase = true)) return null
        return text.substring(MakerPrefix.length).substringBefore('(').trim().trimEnd('.').trim()
            .takeIf { it.isNotEmpty() }
    }

    private const val MakerPrefix = "This file is created using "

    private fun isHeader(cells: List<String>): Boolean {
        val names = cells.map { it.trim().lowercase() }
        return "timestamp" in names && "latitude" in names && "longitude" in names
    }

    @Suppress("MagicNumber")
    private class Columns(names: List<String>) {
        private val lower = names.map { it.lowercase() }
        val timestamp = index("timestamp")
        private val lat = index("latitude")
        private val lon = index("longitude")
        private val speed = index("speed")
        private val bearing = index("bearing", "heading")
        private val altitude = index("altitude")
        private val sats = index("satellites")
        private val forward = index("longitudinal acc", "longitudinal acceleration")
        private val lateral = index("lateral acc", "lateral acceleration")
        private var speedFactor = 3.6
        private var accelFactor = 1.0
        private val extraIdx = lower.indices.filter { i ->
            lower[i].isNotEmpty() && lower[i] !in Ignored && lower.indexOf(lower[i]) == i &&
                i !in setOf(timestamp, lat, lon, speed, bearing, altitude, sats, forward, lateral)
        }.take(ImportLimits.MaxExtraChannels)
        private val extraNames = extraIdx.map { names[it] }
        var extras: List<ExtraColumn> = extraNames.map { ExtraColumn(it, "") }
            private set
        var announced = false

        private fun index(vararg keys: String) = lower.indexOfFirst { it in keys }

        fun units(row: List<String>) {
            val unit = { i: Int -> row.getOrNull(i).orEmpty() }
            speedFactor = when (unit(speed).lowercase()) {
                "km/h", "kph" -> 1.0
                "mph" -> 1.609344
                else -> 3.6 // RaceChrono exports m/s
            }
            val accelUnit = unit(forward).ifEmpty { unit(lateral) }.lowercase()
            accelFactor = if (accelUnit.startsWith("m/s")) 1.0 / 9.80665 else 1.0
            extras = extraIdx.mapIndexed { k, i -> ExtraColumn(extraNames[k], unit(i)) }
        }

        /**
         * Reads the sources row: each extra channel's provenance, and those of the mapped columns,
         * passed to [sink] where the row names one.
         */
        fun sources(row: List<String>, sink: ImportSink) {
            val of = { i: Int -> if (i < 0) null else sourceProvenance(row.getOrNull(i).orEmpty()) }
            extras = extras.mapIndexed { k, e -> e.copy(provenance = of(extraIdx[k])) }
            (of(forward) ?: of(lateral))?.let(sink::accelerationSource)
            val mapped = mapOf(
                GnssChannels.SPEED_KMH.id to speed, GnssChannels.HEADING.id to bearing,
                GnssChannels.ALT_M.id to altitude, GnssChannels.SATS.id to sats,
            )
            for ((id, i) in mapped) of(i)?.let { sink.channelSource(id, it) }
        }

        fun point(cells: List<String>): ImportPoint? {
            fun v(i: Int) = if (i < 0) Double.NaN else cells.getOrNull(i)?.trim()?.toDoubleOrNull() ?: Double.NaN
            val seconds = v(timestamp)
            val la = v(lat)
            val lo = v(lon)
            if (seconds.isNaN() || la.isNaN() || lo.isNaN()) return null
            return ImportPoint(
                utcMicros = Math.round(seconds * 1_000_000.0),
                lat = la,
                lon = lo,
                speedKmh = v(speed) * speedFactor,
                headingDeg = v(bearing),
                altM = v(altitude),
                sats = v(sats),
                accelForwardG = v(forward) * accelFactor,
                accelLateralG = v(lateral) * accelFactor,
                extras = if (extraIdx.isEmpty()) {
                    ImportPoint.EmptyValues
                } else {
                    DoubleArray(extraIdx.size) { v(extraIdx[it]) }
                },
            )
        }
    }

    /**
     * The provenance of a RaceChrono source cell such as "200: obd": the kind after the colon. Null
     * for a kind not known here. A "gps" channel is a GPS's of unknown kind: RaceChrono does not say which.
     */
    private fun sourceProvenance(cell: String): Provenance? = when (cell.substringAfter(':', "").trim().lowercase()) {
        "gps" -> Provenance.MEASURED_GPS
        "acc" -> Provenance.MEASURED_ACCELEROMETER
        "obd" -> Provenance.MEASURED_OBD
        "can" -> Provenance.MEASURED_CAN
        "calc" -> Provenance.CALCULATED
        else -> null
    }

    /** RaceChrono's own lap and distance bookkeeping and position-quality columns not worth a channel. */
    private val Ignored = setOf(
        "fragment id", "lap #", "elapsed time", "distance traveled", "distance", "combined acc",
        "device update rate", "fix type", "trap name",
    )

    /** One CSV line split on commas, honouring double quotes ("" inside quotes is a quote). */
    fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    out += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
