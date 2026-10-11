package com.flappedear.betterdriver.shell

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.SetupStore
import com.flappedear.betterdriver.setup.migrate
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/** A new id for an equipment item or a car. */
fun newSetupId(): String = UUID.randomUUID().toString()

/**
 * Arek's equipment, cars and the next session's choice (FBBD-83), as Compose state for every
 * screen and for the GoPro code. Changes are made on the main thread and saved in the background,
 * one at a time, so the file always holds a whole setup.
 *
 * The first start of a build with this code fills it from the old settings ([AppPrefs.legacy]);
 * a fresh install starts with the phone camera only.
 */
class SetupRepo private constructor(context: Context) {
    private val store = SetupStore(File(context.filesDir, FileName))
    private val saver = Executors.newSingleThreadExecutor { r -> Thread(r, "setup-save").apply { isDaemon = true } }

    // Read once at startup on the main thread: one small file.
    var setup by mutableStateOf(
        store.load() ?: migrate(AppPrefs.legacy(context), ::newSetupId).also { save(store, it) },
    )
        private set

    /**
     * The setup as it was at the last START, so changes made in Settings while recording (a camera
     * added, tiles changed) do not reach the running session. Saved to its own file, so a UI
     * process restarted mid-session reconnects to the same cameras and tiles. Only read while a
     * session runs; the next START replaces it.
     */
    private val runningStore = SetupStore(File(context.filesDir, RunningFileName))
    private var running by mutableStateOf(runningStore.load())

    /** What the running session uses. Falls back to [setup] if no session was ever started. */
    fun forSession(): Setup = running ?: setup

    /** Fixes the session's setup at START. Main thread only; one small file, written at once. */
    fun sessionStarted(): Setup = setup.also {
        running = it
        // Written before the recorder starts, so a UI restarted right after START still finds it.
        try {
            runningStore.save(it)
        } catch (e: IOException) {
            Log.e(Tag, "session setup not saved", e)
        }
    }

    /** Applies [change] and saves the result. Main thread only. */
    fun update(change: (Setup) -> Setup) {
        val next = change(setup)
        if (next == setup) return
        setup = next
        save(store, next)
    }

    /**
     * The Open GoPro camera that recorded as [sourceId] ([CameraIds.goPro]), to download from after
     * a session. Sessions from before FBBD-105 name the one HERO11 of that time; a listed HERO11
     * answers for it.
     */
    fun goProFor(sourceId: String): Equipment? {
        val cameras = setup.equipment.filter { it.model == EquipmentModel.GOPRO && it.address != null }
        return cameras.firstOrNull { CameraIds.goPro(it.id) == sourceId }
            ?: cameras.firstOrNull { sourceId == CameraIds.GOPRO_HERO11 && it.goPro == GoProModel.HERO11_BLACK }
    }

    private fun save(to: SetupStore, s: Setup) = saver.execute {
        try {
            to.save(s)
        } catch (e: IOException) {
            Log.e(Tag, "setup not saved", e)
        }
    }

    companion object {
        private const val FileName = "setup.json"
        private const val RunningFileName = "session-setup.json"
        private const val Tag = "SetupRepo"

        @Volatile
        private var instance: SetupRepo? = null

        fun get(context: Context): SetupRepo = instance ?: synchronized(this) {
            instance ?: SetupRepo(context.applicationContext).also { instance = it }
        }
    }
}
