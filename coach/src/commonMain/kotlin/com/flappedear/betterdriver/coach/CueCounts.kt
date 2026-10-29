package com.flappedear.betterdriver.coach

/**
 * What [rememberCueCounts] found: cues by session id, for sessions with at least one; [changed] when
 * the remembered counts changed and should be saved.
 */
data class CueCounts(val counts: Map<String, Int>, val changed: Boolean)

/**
 * How many cues each of the finished [sessions] has (FBBD-99), so the session list can offer the cue
 * review without reading every session each time. [known] remembers "durationMicros:cues" by session
 * id and is updated in place: a session that is new, or whose length changed (an imported session
 * replaced it), is read with [count]; sessions no longer listed are forgotten.
 */
fun <S> rememberCueCounts(
    sessions: List<S>,
    id: (S) -> String,
    durationMicros: (S) -> Long?,
    known: MutableMap<String, String>,
    count: (S) -> Int,
): CueCounts {
    var changed = false
    val counts = sessions.associate { s ->
        val key = "${durationMicros(s) ?: -1}"
        val cached = known[id(s)]?.takeIf { it.substringBefore(':') == key }?.substringAfter(':')?.toIntOrNull()
        val n = cached ?: count(s).also {
            known[id(s)] = "$key:$it"
            changed = true
        }
        id(s) to n
    }
    val ids = sessions.map(id).toSet()
    if (known.keys.retainAll(ids)) changed = true
    return CueCounts(counts.filterValues { it > 0 }, changed)
}

/**
 * The remembered cue counts as text, one "session id=durationMicros:cues" a line, where there is no
 * java.util.Properties (the iPhone, FBBD-99). A line that does not read is skipped.
 */
object CueCountsText {
    fun encode(known: Map<String, String>): String = known.entries.joinToString("") { "${it.key}=${it.value}\n" }

    fun decode(text: String): MutableMap<String, String> {
        val known = mutableMapOf<String, String>()
        for (line in text.lineSequence()) {
            val at = line.indexOf('=')
            if (at > 0) known[line.substring(0, at)] = line.substring(at + 1)
        }
        return known
    }
}
