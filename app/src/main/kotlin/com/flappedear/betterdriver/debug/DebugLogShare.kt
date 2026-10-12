package com.flappedear.betterdriver.debug

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.flappedear.betterdriver.BuildConfig
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.qualityReports
import com.flappedear.betterdriver.recording.sessionsDir
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** logcat lines added to the zip: the app's own, from all its processes. */
private const val LogcatLines = 20_000
private const val LogcatTimeoutSeconds = 10L

/** Sessions whose validation report goes in the zip, newest first. */
private const val QualityReportsZipped = 5

/**
 * Packs the debug logs into one zip and opens Android's share sheet (FBBD-146), so Arek can drop it
 * into the project or a Jira issue. The zip holds every process's log files, the app's recent
 * logcat (CameraX and Bluetooth messages included), a device line and the validation reports of the
 * newest sessions (FBBD-183). Prepared in the transfer provider's own logs folder, so a session
 * send cleaning its folder cannot delete it mid-share.
 */
object DebugLogShare {
    private fun outDir(context: Context) = File(context.cacheDir, "logs-out")

    /** The size of the log files now, for the settings row. */
    fun logBytes(context: Context): Long =
        AppLog.dir(context).listFiles().orEmpty().filter { it.name.endsWith(".log") }.sumOf { it.length() }

    /** Writes the zip; blocking, so off the main thread. */
    fun prepare(context: Context): File {
        DebugLog.i(Tag, "preparing debug logs to share")
        val out = outDir(context)
        out.deleteRecursively()
        out.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val zip = File(out, "BetterDriver-logs-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("device.txt"))
            z.write(deviceText().toByteArray())
            z.closeEntry()
            AppLog.dir(context).listFiles().orEmpty().filter { it.name.endsWith(".log") }.sortedBy { it.name }
                .forEach { f ->
                    z.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            z.putNextEntry(ZipEntry("logcat.txt"))
            z.write(logcat().toByteArray())
            z.closeEntry()
            sessionReports(context).forEach { (id, json) ->
                z.putNextEntry(ZipEntry("quality/$id.json"))
                z.write(json.toByteArray())
                z.closeEntry()
            }
        }
        return zip
    }

    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".transfer", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        try {
            context.startActivity(Intent.createChooser(send, "Share debug logs"))
        } catch (e: ActivityNotFoundException) {
            DebugLog.w(Tag, "no share target", e)
        }
    }

    /**
     * The validation reports (FBBD-183) of the newest sessions, made for any that has none yet, by
     * session id. A session still recording has none.
     */
    private fun sessionReports(context: Context): List<Pair<String, String>> {
        val reports = qualityReports(context)
        val newest = SessionStore(sessionsDir(context)).list().take(QualityReportsZipped)
        return newest.mapNotNull { s -> reports.load(s.id)?.let { s.id to it.toJson() } }
    }

    private fun deviceText() = buildString {
        append("BetterDriver ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
        append("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} ")
        append("(SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}\n")
        append("Prepared ${Date()}\n")
    }

    /** The app's own logcat; Android lets an app read its own lines without a permission. */
    private fun logcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LogcatLines.toString())
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(LogcatTimeoutSeconds, TimeUnit.SECONDS)
        text
    } catch (e: IOException) {
        "logcat not available: $e\n"
    }

    private const val Tag = "DebugLogs"
}
