package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.vbo.VboWriter

/** Parses a VBO produced by this module and checks the structural rules from Racelogic's VBO page. */
object VboValidator {
    data class Parsed(
        val firstLine: String,
        val sections: List<String>,
        val header: List<String>,
        val units: List<String>,
        val columnNames: List<String>,
        val rows: List<List<String>>,
        val body: Map<String, List<String>>,
    )

    private val ORDER = listOf(
        "[header]", "[channel units]", "[comments]", "[laptiming]", "[avi]",
        "[circuit details]", "[session data]", "[column names]", "[data]",
    )
    private val TIME = Regex("""^\d{6}\.\d{2,3}$""")

    fun parse(text: String): Parsed {
        require(!text.contains(Regex("(?<!\r)\n"))) { "every line must end with CRLF" }
        val lines = text.split("\r\n").dropLastWhile { it.isEmpty() }
        val sections = ArrayList<String>()
        val body = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (l in lines.drop(1)) {
            if (l.startsWith("[") && l.endsWith("]")) {
                sections += l
                current = mutableListOf<String>().also { body[l] = it }
            } else {
                current?.add(l)
            }
        }
        // Each section ends with one blank separator line. Blank units lines are allowed (no unit),
        // so only that one separator is removed there; elsewhere blank lines carry nothing.
        for ((name, ls) in body) {
            if (name == "[channel units]") {
                if (ls.isNotEmpty() && ls.last().isEmpty()) ls.removeAt(ls.size - 1)
            } else {
                ls.removeAll { it.isEmpty() }
            }
        }
        val cols = body["[column names]"]?.firstOrNull()?.split(" ") ?: emptyList()
        return Parsed(
            firstLine = lines.first(),
            sections = sections,
            header = body["[header]"] ?: emptyList(),
            units = body["[channel units]"] ?: emptyList(),
            columnNames = cols,
            rows = (body["[data]"] ?: emptyList()).map { it.split(" ") },
            body = body,
        )
    }

    /** Throws if the file breaks a rule; returns the parsed file otherwise. */
    fun validate(text: String): Parsed {
        val p = parse(text)
        check(p.firstLine.startsWith("File created on ")) { "bad first line: ${p.firstLine}" }
        check(p.sections.toSet().size == p.sections.size) { "duplicate sections: ${p.sections}" }
        check(p.sections.all { it in ORDER }) { "unknown section in ${p.sections}" }
        check(p.sections == p.sections.sortedBy { ORDER.indexOf(it) }) { "sections out of order: ${p.sections}" }
        for (required in listOf("[header]", "[channel units]", "[column names]", "[data]")) {
            check(required in p.sections) { "missing $required" }
        }
        val standard = p.header.count { it in VboWriter.STANDARD_CHANNELS }
        check(p.units.size == p.header.size - standard) {
            "units lines ${p.units.size} != header ${p.header.size} - standard $standard"
        }
        check(p.columnNames.size == p.header.size) { "column names ${p.columnNames.size} != header ${p.header.size}" }
        check(p.columnNames.none { it.isEmpty() })
        val timeIdx = p.header.indexOf("time")
        p.rows.forEachIndexed { i, r ->
            check(r.size == p.columnNames.size) { "row $i has ${r.size} values, expected ${p.columnNames.size}" }
            r.forEach { v -> check(v.toDoubleOrNull() != null) { "row $i: non-numeric '$v'" } }
            if (timeIdx >= 0) check(TIME.matches(r[timeIdx])) { "row $i: bad time '${r[timeIdx]}'" }
        }
        return p
    }
}
