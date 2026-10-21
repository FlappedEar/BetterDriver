package com.flappedear.betterdriver.camera

/**
 * Which of the camera's own result values switch partway through a file (FBBD-278). Arek's 1080p · 60
 * files skip single frames for several minutes and then none, with every processing mode the app
 * can ask for already FAST (2026-10-21 19:53 log); the camera changes something of its own at that
 * minute. Fed a snapshot of every result key every [SnapshotNanos], this names each key whose value
 * held for [stableSnapshots] snapshots in a row and then changed, so values that move every frame
 * (exposure, gains, timestamps) never show and a switch does. At most [maxLines] lines per file.
 */
class ResultChanges(private val stableSnapshots: Int = 6, private val maxLines: Int = 40) {
    private val last = HashMap<String, String>()
    private val held = HashMap<String, Int>()
    private var lines = 0
    private var lastNanos = 0L

    /** Whether a capture at sensor time [sensorNanos] should be taken as the next snapshot. */
    @Synchronized
    fun due(sensorNanos: Long): Boolean {
        if (lastNanos != 0L && sensorNanos - lastNanos < SnapshotNanos) return false
        lastNanos = sensorNanos
        return true
    }

    /** Takes [values] (key name to its value as text) as the next snapshot; returns a line per switch. */
    @Synchronized
    fun snapshot(values: Map<String, String>): List<String> {
        val switched = mutableListOf<String>()
        for (key in values.keys + last.keys) {
            val now = values[key] ?: Absent
            val before = last.put(key, now)
            when {
                before == null -> held[key] = 0
                before == now -> held[key] = (held[key] ?: 0) + 1
                else -> {
                    if ((held[key] ?: 0) >= stableSnapshots && lines < maxLines) {
                        switched += "camera result switched: $key $before -> $now"
                        lines++
                    }
                    held[key] = 0
                }
            }
        }
        return switched
    }

    companion object {
        /** Ten seconds between snapshots: a switch shows within that of when it happened. */
        const val SnapshotNanos = 10_000_000_000L
        private const val Absent = "absent"
        private const val MaxValueChars = 160

        /** A result value as short text; null for values too long to compare (maps, face lists). */
        fun text(value: Any?): String? {
            val text = when (value) {
                null -> "null"
                is IntArray -> value.contentToString()
                is LongArray -> value.contentToString()
                is FloatArray -> value.contentToString()
                is DoubleArray -> value.contentToString()
                is ByteArray -> value.contentToString()
                is BooleanArray -> value.contentToString()
                is Array<*> -> value.contentDeepToString()
                else -> value.toString()
            }
            return text.takeIf { it.length <= MaxValueChars }
        }
    }
}
