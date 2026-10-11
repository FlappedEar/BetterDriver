package com.flappedear.betterdriver.shell

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.setup.EquipmentModel
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
    var setup by mutableStateOf(store.load() ?: migrate(AppPrefs.legacy(context), ::newSetupId).also { save(it) })
        private set

    /**
     * The setup as it was when the running session started, so changes made in Settings while
     * recording (a camera added, tiles changed) do not show as part of that session. Null when no
     * session was started from this process; [forSession] then falls back to [setup].
     */
    private var running by mutableStateOf<Setup?>(null)

    /** What the running session uses, or the next session's choice when none runs. */
    fun forSession(): Setup = running ?: setup

    /** Fixes the session's setup at START. Main thread only. */
    fun sessionStarted(): Setup = setup.also { running = it }

    /** Called when the recorder reports no running session. Main thread only. */
    fun sessionEnded() {
        running = null
    }

    /** Applies [change] and saves the result. Main thread only. */
    fun update(change: (Setup) -> Setup) {
        val next = change(setup)
        if (next == setup) return
        setup = next
        save(next)
    }

    /** The HERO11 the app downloads from after a session. At most one is listed ([EquipmentModel.single]). */
    fun hero11Address(): String? = setup.equipment.firstOrNull { it.model == EquipmentModel.GOPRO_HERO11 }?.address

    private fun save(s: Setup) = saver.execute {
        try {
            store.save(s)
        } catch (e: IOException) {
            Log.e(Tag, "setup not saved", e)
        }
    }

    companion object {
        private const val FileName = "setup.json"
        private const val Tag = "SetupRepo"

        @Volatile
        private var instance: SetupRepo? = null

        fun get(context: Context): SetupRepo = instance ?: synchronized(this) {
            instance ?: SetupRepo(context.applicationContext).also { instance = it }
        }
    }
}
