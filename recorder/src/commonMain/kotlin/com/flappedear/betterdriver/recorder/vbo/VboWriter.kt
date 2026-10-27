@file:Suppress("MagicNumber") // Field widths and unit conversions of the VBO format.

package com.flappedear.betterdriver.recorder.vbo

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.format.signedFixed
import com.flappedear.betterdriver.math.roundHalfUp

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
    /** When the session started, UTC epoch milliseconds; the file's date line. */
    val createdUtcMillis: Long,
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
class VboWriter(private val out: Appendable) {

    companion object {
        const val CRLF = "\r\n"

        /** Header names of the standard channels this app writes. */
        val STANDARD_CHANNELS = setOf(
            "satellites", "time", "latitude", "longitude", "velocity kmh", "heading", "height",
            "avifileindex", "avisynctime",
        )

        fun sats(v: Double) = vboPadded(v.toLong().coerceIn(0, 999), 3)

        /** UTC milliseconds of day → hhmmss.sss. */
        fun time(utcMillis: Double): String {
            val msOfDay = roundHalfUp(utcMillis).mod(MillisPerDay)
            val h = msOfDay / 3_600_000
            val m = msOfDay / 60_000 % 60
            val s = msOfDay / 1_000 % 60
            val ms = msOfDay % 1_000
            return vboPadded(h, 2) + vboPadded(m, 2) + vboPadded(s, 2) + "." + vboPadded(ms, 3)
        }

        /** Degrees north → VBO latitude: minutes, 5 decimals. */
        fun latitude(deg: Double) = vboPadded(deg * 60.0, 5, 12, plus = true)

        /** Degrees east → VBO longitude: minutes with the hemisphere reversed (west positive), 5 decimals. */
        fun longitude(deg: Double) = vboPadded(-deg * 60.0, 5, 12, plus = true)

        fun velocity(kmh: Double) = vboPadded(kmh, 3, 7)

        fun heading(deg: Double): String {
            // Rounded before wrapping: 359.996° is written 000.00, not 360.00 (FBBD-203).
            val rounded = roundHalfUp((((deg % 360.0) + 360.0) % 360.0) * 100.0) / 100.0
            return vboPadded(if (rounded >= 360.0) 0.0 else rounded, 2, 6)
        }

        fun height(m: Double) = vboPadded(m, 2, 9, plus = true)

        /** 1-based video file number, 4 digits as in Racelogic's example. */
        fun aviFileIndex(v: Double) = vboPadded(v.toLong().coerceIn(0, 9_999), 4)

        /** Milliseconds into the video file, 9 characters as in Racelogic's example ("000000266", "-00000001"). */
        fun aviSyncMillis(v: Double) = vboPadded(roundHalfUp(v), 9)

        fun decimal(decimals: Int): (Double) -> String = { v -> fixed(v, decimals) }

        private const val MillisPerDay = 86_400_000L
    }

    fun write(doc: VboDocument) {
        val columns = doc.columns
        line("File created on ${vboDate(doc.createdUtcMillis)}")
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
                val points = listOf(g.long1, g.lat1, g.long2, g.lat2).joinToString(" ") { signedFixed(it, 6) }
                line("${g.type.padEnd(7)} $points ¬ ${oneLine(g.name)}")
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
    }

    /** The format is line-based: free text must not be able to start a new line or a fake section. */
    private fun oneLine(s: String) = s.replace('\r', ' ').replace('\n', ' ')

    private fun line(s: String) {
        out.append(s)
        out.append(CRLF)
    }

    private fun blank() {
        out.append(CRLF)
    }
}
