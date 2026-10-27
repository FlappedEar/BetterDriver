package com.flappedear.betterdriver.recorder.vbo

import com.flappedear.betterdriver.recorder.store.SessionData
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * [VboExport] to a stream, as UTF-8: the Android app's exports and the JVM tests. The format itself
 * is common code, so the iPhone writes the same file (FBBD-333).
 */
object VboExporter {

    /** What an export wrote (FBBD-14): [rows] data rows for the session's [fixes] GNSS fixes it covers. */
    data class Written(val rows: Int, val fixes: Int)

    fun export(
        session: SessionData,
        out: OutputStream,
        video: VboVideo? = null,
        derived: List<VboDerivedChannel> = emptyList(),
    ): Written {
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        val written = VboExport.export(session, writer, video, derived)
        writer.flush()
        return Written(written.rows, written.fixes)
    }
}
