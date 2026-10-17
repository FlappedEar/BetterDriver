package com.flappedear.betterdriver.debug

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.flappedear.betterdriver.BuildConfig
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.log.LogFile
import java.io.File

private const val NanosPerMicro = 1_000L
private const val BytesPerMegabyte = 1L shl 20

/**
 * The app's log, Android side (FBBD-146, FBBD-183): one continuous file, files/logs/betterdriver.log,
 * that every process (UI, recorder, camera, coach) appends to across sessions and app restarts.
 * Each line carries UTC and boot-clock time, the process, and the recording it belongs to. Lines also
 * go to logcat. Uncaught exceptions are written before the process dies. Nothing deletes the log
 * except Settings › Diagnostics › Clear log ([clear]); the per-process files of older builds stay
 * beside it until then.
 */
object AppLog {
    fun dir(context: Context) = File(context.filesDir, "logs")

    /** The continuous log. */
    fun file(context: Context) = File(dir(context), FileName)

    /** The log and what older builds left in its folder. Reads the disk: off the main thread. */
    fun files(context: Context): List<File> =
        dir(context).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".log") }.sortedBy { it.name }

    /** Their size in bytes. Off the main thread. */
    fun bytes(context: Context): Long = files(context).sumOf { it.length() }

    /** Deletes the log; the next line starts it again. Only on the user's say-so. Off the main thread. */
    fun clear(context: Context) {
        files(context).forEach { it.delete() }
        File(dir(context), "off").delete() // The switch of older builds: the log is always on now.
        DebugLog.i(Tag, "log cleared")
    }

    /** Returns the process name ("com.flappedear.betterdriver:recorder"). */
    fun install(app: Application): String {
        val process = processName(app)
        val name = process.substringAfter(':', "main")
        DebugLog.echo = { level, tag, message, error ->
            Log.println(level, tag, if (error == null) message else message + "\n" + Log.getStackTraceString(error))
        }
        DebugLog.install(
            LogFile(
                file(app),
                name,
                pid = Process.myPid(),
                uptimeMicros = { SystemClock.elapsedRealtimeNanos() / NanosPerMicro },
            ),
        )
        DebugLog.i(
            Tag,
            "start $process: BetterDriver ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) on " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                // How much memory Android lets this process use (FBBD-232: a long export ran out at 256 MB).
                "heap ${Runtime.getRuntime().maxMemory() / BytesPerMegabyte} MB",
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DebugLog.e("Crash", "uncaught in thread ${thread.name}", error)
            DebugLog.flush()
            previous?.uncaughtException(thread, error)
        }
        return process
    }

    private fun processName(app: Application): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val pid = Process.myPid()
        return app.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }?.processName ?: app.packageName
    }

    private const val Tag = "App"
    private const val FileName = "betterdriver.log"
}
