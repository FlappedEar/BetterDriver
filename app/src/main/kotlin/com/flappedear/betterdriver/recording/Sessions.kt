package com.flappedear.betterdriver.recording

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import java.io.IOException
import java.util.concurrent.Executors

/** Export progress of one session, shown on its card. */
enum class ExportState { Running, Done, Failed }

/**
 * Saved sessions, for the UI process. Created once per process; creating it recovers interrupted
 * sessions (FBBD-71). Disk work runs on one background thread; results are Compose state.
 * Recovery and export only read chunk files, so they can run while the recorder process records.
 */
class Sessions private constructor(private val context: Context) {
    private val store = SessionStore(sessionsDir(context))
    private val io = Executors.newSingleThreadExecutor { Thread(it, "sessions-io") }
    private val main = Handler(Looper.getMainLooper())

    /** Newest first. */
    var list by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    /** By session id. */
    val exports = mutableStateMapOf<String, ExportState>()

    init {
        recoverAndRefresh()
    }

    /**
     * Marks sessions that were not stopped cleanly and that no process is recording as recovered,
     * then reloads the list. A session being recorded is locked, so it is never touched.
     */
    @Suppress("TooGenericExceptionCaught") // A damaged session must not stop the others from listing.
    fun recoverAndRefresh() = io.execute {
        try {
            store.recoverInterrupted()
        } catch (e: RuntimeException) {
            android.util.Log.w(Tag, "recovery failed", e)
        }
        publish()
    }

    fun refresh() = io.execute { publish() }

    /** Writes the session as VBO to [uri], a document the user picked. */
    fun export(id: String, uri: Uri) {
        exports[id] = ExportState.Running
        io.execute {
            val result = try {
                val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
                out.use { VboExporter.export(store.read(id), it) }
                ExportState.Done
            } catch (e: IOException) {
                android.util.Log.w(Tag, "export failed", e)
                ExportState.Failed
            } catch (e: IllegalStateException) {
                android.util.Log.w(Tag, "export failed", e) // unreadable metadata
                ExportState.Failed
            }
            main.post { exports[id] = result }
        }
    }

    private fun publish() {
        val sessions = store.list()
        main.post { list = sessions }
    }

    companion object {
        private const val Tag = "Sessions"

        @Volatile
        private var instance: Sessions? = null

        fun get(context: Context): Sessions = instance ?: synchronized(this) {
            instance ?: Sessions(context.applicationContext).also { instance = it }
        }
    }
}

/** The file name offered when exporting. */
fun vboFileName(session: SessionSummary) = "BetterDriver-${session.id}.vbo"
