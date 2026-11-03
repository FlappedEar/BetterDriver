package com.flappedear.betterdriver.recording

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.flappedear.betterdriver.recorder.export.ExportFormat
import com.flappedear.betterdriver.recorder.export.ExportShare
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionSummary
import java.io.File

/**
 * Export from a session card: [Sessions] writes the VBO, VBO + video, CSV or GPX in the background
 * (FBBD-150), then Android's share sheet opens, as Share log does (FBBD-443): Quick Share, Drive,
 * Gmail or another app in one step, the same route as on the iPhone. VBO + video shares the VBO and
 * each video file, so saving them keeps them side by side for Circuit Tools (FBBD-25).
 */
@Composable
fun rememberSessionExport(sessions: Sessions): (SessionSummary, ExportFormat) -> Unit {
    val context = LocalContext.current
    val ready = sessions.ready
    LaunchedEffect(ready) {
        if (ready != null) {
            shareExport(context, ready)
            sessions.shared(ready)
        }
    }
    return { s, format -> sessions.export(s.id, format) }
}

/** Opens the share sheet for a written export: one file, or several for VBO + video. */
internal fun shareExport(context: Context, share: ExportShare) {
    val uris = share.files.map { FileProvider.getUriForFile(context, context.packageName + ".transfer", File(it)) }
    val send = sendIntent(share, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, share.title))
    } catch (e: ActivityNotFoundException) {
        DebugLog.w("Sessions", "no share target", e)
    }
}

private fun sendIntent(share: ExportShare, uris: List<Uri>): Intent {
    val clip = ClipData.newRawUri(null, uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.clipData = clip
    return send.setType(share.mimeType).putExtra(Intent.EXTRA_SUBJECT, share.subject)
}
