package com.flappedear.betterdriver.debug

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.flappedear.betterdriver.BuildConfig
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.log.LogFile
import java.io.File

/** How often a process looks again whether the debug log was switched on or off. */
private const val SwitchCheckMs = 5_000L

/**
 * The debug log's Android side (FBBD-146). Each process writes its own rotating file in
 * files/logs: main.log for the UI, recorder.log, camera.log and coach.log. Lines also go to logcat.
 * Uncaught exceptions are written before the process dies. The log is on unless Arek switched it
 * off in Settings › App, which leaves a marker file every process sees.
 */
object AppLog {
    fun dir(context: Context) = File(context.filesDir, "logs")

    private fun offMarker(context: Context) = File(dir(context), "off")

    /** Reads a file: off the main thread. */
    fun enabled(context: Context): Boolean = !offMarker(context).exists()

    /** Writes a file: off the main thread. */
    fun setEnabled(context: Context, on: Boolean) {
        val marker = offMarker(context)
        if (on) {
            marker.delete()
            DebugLog.i(Tag, "debug log switched on")
        } else {
            DebugLog.i(Tag, "debug log switched off")
            marker.parentFile?.mkdirs()
            marker.createNewFile()
        }
    }

    /** Returns the process name ("com.flappedear.betterdriver:recorder"). */
    fun install(app: Application): String {
        val process = processName(app)
        val name = process.substringAfter(':', "main")
        DebugLog.echo = { level, tag, message, error ->
            Log.println(level, tag, if (error == null) message else message + "\n" + Log.getStackTraceString(error))
        }
        DebugLog.install(LogFile(dir(app), name, enabled = cachedSwitch(app), pid = Process.myPid()))
        DebugLog.i(
            Tag,
            "start $process: BetterDriver ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) on " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DebugLog.e("Crash", "uncaught in thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        return process
    }

    /** The switch, read from disk at most every [SwitchCheckMs], so a line costs no file check. */
    private fun cachedSwitch(context: Context): () -> Boolean {
        var on = enabled(context)
        var checkedAt = System.currentTimeMillis()
        return {
            val now = System.currentTimeMillis()
            if (now - checkedAt >= SwitchCheckMs) {
                on = enabled(context)
                checkedAt = now
            }
            on
        }
    }

    private fun processName(app: Application): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val pid = Process.myPid()
        return app.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }?.processName ?: app.packageName
    }

    private const val Tag = "App"
}
