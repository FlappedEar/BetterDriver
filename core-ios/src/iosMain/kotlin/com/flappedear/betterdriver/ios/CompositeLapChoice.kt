package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.optimal.CompositeOptions
import com.flappedear.betterdriver.analysis.optimal.OptimalOption
import com.flappedear.betterdriver.analysis.optimal.PartOnVideo
import com.flappedear.betterdriver.analysis.optimal.compositeOptions
import com.flappedear.betterdriver.analysis.optimal.missingText
import com.flappedear.betterdriver.analysis.optimal.optimalOptionText
import com.flappedear.betterdriver.analysis.optimal.optimalPartText
import com.flappedear.betterdriver.analysis.optimal.optimalVideoNote

/**
 * The composite lap video under the lap times (FBBD-163, FBBD-178; iOS FBBD-370), as Android's lap
 * analysis offers it, in the words the shared core gives it: the button's [note], the sheet's
 * [kinds] (the realistic optimal first) and the one it starts on ([preselected]). Null [options]:
 * the parts are still being looked for on video.
 */
class CompositeLapChoice(options: CompositeOptions?) {
    /** Some composite lap can be filmed: the button is on. */
    val enabled: Boolean = options?.enabled == true

    /** The line beside the button. */
    val note: String = optimalVideoNote(options)

    /** The sheet's choices: the realistic optimal, then the raw theoretical best. */
    val kinds: List<CompositeKindLine> =
        options?.let { listOf(kindLine(it.realistic), kindLine(it.raw)) }.orEmpty()

    /** The video part the sheet starts on (DataVideoSession's -1 or -2); 0 while there is no choice. */
    val preselected: Int = options?.let { DataVideoSession.partOf(it.preselected.kind) } ?: 0

    private fun kindLine(o: OptimalOption): CompositeKindLine {
        val (name, note) = optimalOptionText(o)
        val parts = o.parts.map { p ->
            val (detail, video) = optimalPartText(p)
            CompositePartLine(p.name, detail, video, p.video == PartOnVideo.None)
        }
        val part = DataVideoSession.partOf(o.kind)
        return CompositeKindLine(part, name, note, o.unavailable, missingText(o), parts)
    }
}

/**
 * The composite laps of [session] as the lap analysis offers them under the lap times (FBBD-370, as
 * Android's): [durations] as for DataVideoSession.parts. Analyses the session the first time: not on
 * the main thread.
 */
fun compositeOptionsOf(session: DataVideoSession, durations: Map<String, Long>): CompositeOptions =
    compositeOptions(session.composites(durations))

/**
 * One composite lap in the sheet: its video [part], "Realistic optimal · 1:39.43" ([name]) over
 * what it is made of ([note]); [unavailable] says why it cannot be made, [missing] what it skips.
 */
class CompositeKindLine(
    val part: Int,
    val name: String,
    val note: String,
    val unavailable: String?,
    val missing: String?,
    val parts: List<CompositePartLine>,
) {
    /** It can be filmed. */
    val enabled: Boolean get() = unavailable == null
}

/** "Corner 3   Lap 5 · 0:05.12   No video": [video] is null when the part is whole on video. */
class CompositePartLine(val name: String, val detail: String, val video: String?, val noVideo: Boolean)
