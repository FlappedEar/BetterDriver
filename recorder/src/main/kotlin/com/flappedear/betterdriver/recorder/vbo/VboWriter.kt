@file:Suppress("MagicNumber") // Field widths and unit conversions of the VBO format.

package com.flappedear.betterdriver.recorder.vbo

import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One VBO column. Standard Racelogic channels are recognised by [headerName] and get no units line;
 * every other column needs one (FBBD-50, units count rule).
 */
data class VboColumn(
    val headerName: String,
    val columnName: String,
    val unit: String,
    val format: (Double) -> String,
) {
    val isStandard: Boolean get() = headerName in VboWriter.STANDARD_CHANNELS

    init {
        require(columnName.none { it.isWhitespace() }) { "column names cannot contain spaces: '$columnName'" }
        require(headerName.isNotBlank())
    }
}

/** A lap timing gate in VBO units (minutes; longitude positive west). */
data class VboGate(
    val type: String,
    val long1: Double,
    val lat1: Double,
    val long2: Double,
    val lat2: Double,
    val name: String,
)

/** Everything one VBO file holds. Sections with nothing in them are left out. */
data class VboDocument(
    val created: Instant,
    val columns: List<VboColumn>,
    val comments: List<String> = emptyList(),
    val gates: List<VboGate> = emptyList(),
    val aviBaseName: String? = null,
    /** Written on the line after [aviBaseName] when set, as Racelogic-compatible tools do (for example "MP4"). */
    val aviExtension: String? = null,
    val rows: Sequence<DoubleArray>,
)

/**
 * Writes a VBO text file following Racelogic's VBO file structure page
 * (https://developers.racelogic.co.uk/Portal/Tutorials/tutorialVBOfilestructure.html):
 * date line, [header], [channel units], [comments], [laptiming], [avi], [column names], [data],
 * CRLF line endings, blank line between sections.
 */
class VboWriter(private val out: Writer) {

    companion object {
        const val CRLF = "\r\n"

        /** Header names of the standard channels this app writes. */
        val STANDARD_CHANNELS = setOf(
            "satellites", "time", "latitude", "longitude", "velocity kmh", "heading", "height",
            "avifileindex", "avisynctime",
        )

        private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy '@' HH:mm:ss").withZone(ZoneOffset.UTC)

        fun sats(v: Double) = String.format(Locale.ROOT, "%03d", v.toLong().coerceIn(0, 999))

        /** UTC milliseconds of day → hhmmss.sss. */
        fun time(utcMillis: Double): String {
            val msOfDay = Math.floorMod(Math.round(utcMillis), 86_400_000L)
            val h = msOfDay / 3_600_000
            val m = msOfDay / 60_000 % 60
            val s = msOfDay / 1_000 % 60
            val ms = msOfDay % 1_000
            return String.format(Locale.ROOT, "%02d%02d%02d.%03d", h, m, s, ms)
        }

        /** Degrees north → VBO latitude: minutes, 5 decimals. */
        fun latitude(deg: Double) = String.format(Locale.ROOT, "%+012.5f", deg * 60.0)

        /** Degrees east → VBO longitude: minutes with the hemisphere reversed (west positive), 5 decimals. */
        fun longitude(deg: Double) = String.format(Locale.ROOT, "%+012.5f", -deg * 60.0)

        fun velocity(kmh: Double) = String.format(Locale.ROOT, "%07.3f", kmh)

        fun heading(deg: Double): String {
            // Rounded before wrapping: 359.996° is written 000.00, not 360.00 (FBBD-203).
            val rounded = Math.round((((deg % 360.0) + 360.0) % 360.0) * 100.0) / 100.0
            return String.format(Locale.ROOT, "%06.2f", if (rounded >= 360.0) 0.0 else rounded)
        }

        fun height(m: Double) = String.format(Locale.ROOT, "%+09.2f", m)
        /** 1-based video file number, 4 digits as in Racelogic's example. */
        fun aviFileIndex(v: Double) = String.format(Locale.ROOT, "%04d", v.toLong().coerceIn(0, 9_999))

        /** Milliseconds into the video file, 9 characters as in Racelogic's example ("000000266", "-00000001"). */
        fun aviSyncMillis(v: Double) = String.format(Locale.ROOT, "%09d", Math.round(v))

        fun decimal(decimals: Int): (Double) -> String = { v -> String.format(Locale.ROOT, "%.${decimals}f", v) }
    }

    fun write(doc: VboDocument) {
        val columns = doc.columns
        line("File created on ${DATE.format(doc.created)}")
        blank()

        line("[header]")
        columns.forEach { line(it.headerName) }
        blank()

        line("[channel units]")
        columns.filterNot { it.isStandard }.forEach { line(oneLine(it.unit)) }
        blank()

        if (doc.comments.isNotEmpty()) {
            line("[comments]")
            doc.comments.forEach { line(oneLine(it)) }
            blank()
        }

        if (doc.gates.isNotEmpty()) {
            line("[laptiming]")
            doc.gates.forEach { g ->
                line(
                    String.format(
                        Locale.ROOT, "%-7s %+.6f %+.6f %+.6f %+.6f ¬ %s",
                        g.type, g.long1, g.lat1, g.long2, g.lat2, oneLine(g.name),
                    ),
                )
            }
            blank()
        }

        if (doc.aviBaseName != null) {
            line("[avi]")
            line(oneLine(doc.aviBaseName))
            doc.aviExtension?.let { line(oneLine(it)) }
            blank()
        }

        line("[column names]")
        line(columns.joinToString(" ") { it.columnName })
        blank()

        line("[data]")
        for (row in doc.rows) {
            require(row.size == columns.size) { "row has ${row.size} values for ${columns.size} columns" }
            val sb = StringBuilder()
            for (i in columns.indices) {
                if (i > 0) sb.append(' ')
                sb.append(columns[i].format(row[i]))
            }
            line(sb.toString())
        }
        out.flush()
    }

    /** The format is line-based: free text must not be able to start a new line or a fake section. */
    private fun oneLine(s: String) = s.replace('\r', ' ').replace('\n', ' ')

    private fun line(s: String) {
        out.write(s)
        out.write(CRLF)
    }

    private fun blank() = out.write(CRLF)
}
