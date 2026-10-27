package com.flappedear.betterdriver.io

private const val ByteMask = 0xFF
private const val PrintableMin = 0x20
private const val PrintableMax = 0x7E
private const val HexDigits = 4
private const val HexRadix = 16
private const val HexBits = 4

private const val Hex = "0123456789ABCDEF"

/** Characters written as a backslash and a letter, as Properties.store writes them. */
private val Escapes = mapOf('\t' to 't', '\n' to 'n', '\r' to 'r', '\u000C' to 'f')

/** Characters written after a backslash as they are. */
private const val Specials = "=:#!\\"

/** What a backslash and a letter read as. */
private val Unescapes = Escapes.entries.associate { (c, letter) -> letter to c }

/**
 * [map] in the java.util.Properties file format, as Properties.store(OutputStream) writes it:
 * ASCII only, everything else as \uXXXX, so the bytes read the same as ISO-8859-1 and UTF-8.
 * Session metadata (meta.properties) is written so on Android and iOS (FBBD-332). No date
 * comment, which readers skip anyway.
 */
fun writeProperties(map: Map<String, String>): ByteArray {
    val out = StringBuilder()
    for ((k, v) in map) {
        escape(out, k, isKey = true)
        out.append('=')
        escape(out, v, isKey = false)
        out.append('\n')
    }
    return out.toString().encodeToByteArray()
}

private fun escape(out: StringBuilder, s: String, isKey: Boolean) {
    for ((i, c) in s.withIndex()) {
        val letter = Escapes[c]
        when {
            c == ' ' -> out.append(if (i == 0 || isKey) "\\ " else " ")
            letter != null -> out.append('\\').append(letter)
            c in Specials -> out.append('\\').append(c)
            c.code < PrintableMin || c.code > PrintableMax -> {
                out.append("\\u")
                for (shift in (HexDigits - 1) downTo 0) {
                    out.append(Hex[(c.code shr (shift * HexBits)) and (HexRadix - 1)])
                }
            }
            else -> out.append(c)
        }
    }
}

/**
 * A java.util.Properties file read as Properties.load(InputStream) reads it: ISO-8859-1 bytes,
 * comments, line continuations and escapes. Throws IllegalArgumentException on a malformed \u
 * escape, as load does. A key given twice keeps its last value.
 */
fun parseProperties(bytes: ByteArray): Map<String, String> {
    val text = CharArray(bytes.size) { (bytes[it].toInt() and ByteMask).toChar() }.concatToString()
    val result = LinkedHashMap<String, String>()
    for (line in logicalLines(text)) {
        val (key, value) = splitEntry(line)
        result[unescape(key)] = unescape(value)
    }
    return result
}

private fun isWhite(c: Char) = c == ' ' || c == '\t' || c == '\u000C'


/** Natural lines joined where one ends in an odd number of backslashes; blank and comment lines dropped. */
private fun logicalLines(text: String): List<String> {
    val lines = text.split("\r\n", "\n", "\r")
    val out = ArrayList<String>()
    var pending: StringBuilder? = null
    for (raw in lines) {
        val line = raw.dropWhile(::isWhite)
        val comment = line.isEmpty() || line[0] == '#' || line[0] == '!'
        if (pending == null && comment) continue
        val backslashes = line.length - line.trimEnd('\\').length
        val continues = backslashes % 2 == 1
        val body = if (continues) line.dropLast(1) else line
        val acc = (pending ?: StringBuilder()).append(body)
        if (continues) {
            pending = acc
        } else {
            out += acc.toString()
            pending = null
        }
    }
    pending?.let { out += it.toString() }
    return out
}

/** The raw key and value of one logical line, split at the first unescaped =, : or whitespace. */
private fun splitEntry(line: String): Pair<String, String> {
    val end = keyEnd(line)
    var j = skipWhite(line, end)
    if (j < line.length && (line[j] == '=' || line[j] == ':')) j = skipWhite(line, j + 1)
    return line.substring(0, end) to line.substring(j)
}

/** Where the key of [line] ends: its first =, : or whitespace not escaped by a backslash. */
private fun keyEnd(line: String): Int {
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c == '\\') {
            i += 2
            continue
        }
        if (c == '=' || c == ':' || isWhite(c)) return i
        i++
    }
    return line.length
}

private fun skipWhite(line: String, from: Int): Int {
    var j = from
    while (j < line.length && isWhite(line[j])) j++
    return j
}

private fun unescape(s: String): String {
    if ('\\' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i++]
        when {
            c != '\\' -> out.append(c)
            // A lone backslash at the very end reads as nothing.
            i >= s.length -> Unit
            s[i] == 'u' -> {
                out.append(unicodeAt(s, i + 1))
                i += 1 + HexDigits
            }
            else -> {
                val e = s[i++]
                out.append(Unescapes[e] ?: e)
            }
        }
    }
    return out.toString()
}

/** The \uXXXX escape whose hex digits start at [from]. */
private fun unicodeAt(s: String, from: Int): Char {
    require(from + HexDigits <= s.length) { "Malformed \\uxxxx encoding." }
    val digits = s.substring(from, from + HexDigits)
    val code = if (digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) digits.toInt(HexRadix) else null
    require(code != null) { "Malformed \\uxxxx encoding." }
    return code.toChar()
}
