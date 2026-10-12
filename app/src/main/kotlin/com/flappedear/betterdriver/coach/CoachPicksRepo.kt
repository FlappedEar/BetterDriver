package com.flappedear.betterdriver.coach

import android.content.Context
import com.flappedear.betterdriver.recorder.log.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The corners picked for the spoken coach (FBBD-148) as the UI sees them, by track id. The files are
 * read and written on one background thread in order, so two quick toggles never undo each other; the
 * coach process reads them at the next session start.
 */
internal class CoachPicksRepo private constructor(context: Context) {
    private val store = coachPicks(context)
    private val scope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { Thread(it, "coach-picks") }.asCoroutineDispatcher(),
    )
    private val state = MutableStateFlow<Map<String, List<CornerPick>>>(emptyMap())

    /** The picks of each track read so far; a track read and without picks maps to an empty list. */
    val picks: StateFlow<Map<String, List<CornerPick>>> = state.asStateFlow()

    /** Reads [trackId]'s picks into [picks]. */
    fun load(trackId: String) {
        scope.launch {
            val p = store.get(trackId)
            state.update { it + (trackId to p) }
        }
    }

    /** Reads every track's picks into [picks]. */
    fun loadAll() {
        scope.launch { state.value = store.all() }
    }

    /** Picks [pick] at [trackId], or unpicks it (and anything else at the same corner) if it was picked. */
    fun toggle(trackId: String, pick: CornerPick) = change(trackId) { it.toggled(pick) }

    fun clear(trackId: String) = change(trackId) { emptyList() }

    private fun change(trackId: String, how: (List<CornerPick>) -> List<CornerPick>) {
        scope.launch {
            val next = how(store.get(trackId))
            try {
                store.save(trackId, next)
            } catch (e: IOException) {
                DebugLog.w(Tag, "coach picks for $trackId not saved", e)
                return@launch
            }
            state.update { it + (trackId to next) }
        }
    }

    companion object {
        private const val Tag = "CoachPicks"

        @Volatile
        private var instance: CoachPicksRepo? = null

        fun get(context: Context): CoachPicksRepo = instance ?: synchronized(this) {
            instance ?: CoachPicksRepo(context.applicationContext).also { instance = it }
        }
    }
}
