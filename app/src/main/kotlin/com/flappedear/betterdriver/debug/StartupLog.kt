package com.flappedear.betterdriver.debug

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import com.flappedear.betterdriver.recorder.log.AppLifeLog
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.log.ProcessExit

/**
 * Puts the app's cold start and how its processes ended into the continuous log (FBBD-41), for the
 * drive's checks to grade ([AppLifeLog]). Every build, as the frames are (FBBD-62).
 *
 * A cold start is timed from the process start to the first frame of the screen, and only when the
 * process was started to show it: a process started for the recorder, a screen Android brought back
 * from saved state, or a warm start is not timed.
 */
object StartupLog {
    /** Set in Application.onCreate of the UI process: Android started it in the foreground, for a screen. */
    @Volatile
    private var startedForScreen = false

    /** The first screen of this process was seen; only it can be a cold start. Main thread. */
    private var screenSeen = false

    /** From Application.onCreate of the UI process, on its main thread. */
    fun onUiProcessStart(context: Context) {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        startedForScreen = state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val app = context.applicationContext
        Thread({ logExits(app) }, "exit-reasons").apply { isDaemon = true }.start()
    }

    /** From the first activity's onCreate, after setContent; [restored] when it comes back from saved state. */
    fun onScreenCreated(activity: Activity, restored: Boolean) {
        if (screenSeen) return
        screenSeen = true
        if (!startedForScreen || restored) return
        val startMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Process.getStartRequestedElapsedRealtime()
        } else {
            Process.getStartElapsedRealtime()
        }
        val view = activity.window.decorView
        val main = Handler(Looper.getMainLooper())
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var drawn = false

            override fun onDraw() {
                if (drawn) return
                drawn = true
                // Right after this first frame is drawn; the listener cannot be removed while it runs.
                main.postAtFrontOfQueue {
                    DebugLog.i(AppLifeLog.STARTUP, AppLifeLog.coldStart(SystemClock.elapsedRealtime() - startMillis))
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
    }

    /**
     * Logs the process ends Android recorded since the last ones logged, and the read itself, so a
     * drive's ANRs can be graded. Off the main thread: it asks the system and reads a file. Any
     * process: each keeps its own copy of the mark, so an end may be logged twice (the checks count
     * it once) but never skipped.
     */
    fun logExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            DebugLog.i(AppLifeLog.EXIT, AppLifeLog.check(Build.VERSION.SDK_INT, null))
            return
        }
        val prefs = context.getSharedPreferences(PrefsFile, Context.MODE_PRIVATE)
        val since = prefs.getLong(KeyLoggedUntil, 0L)
        val exits = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        } catch (e: SecurityException) {
            DebugLog.w(AppLifeLog.EXIT, "exit reasons not readable", e)
            null
        }
        if (exits == null) {
            // Not a read of no ends: the drive checks must not grade ANRs from it.
            DebugLog.i(AppLifeLog.EXIT, AppLifeLog.checkFailed(Build.VERSION.SDK_INT))
            return
        }
        val new = exits.filter { it.timestamp > since }.sortedBy { it.timestamp }
        DebugLog.i(AppLifeLog.EXIT, AppLifeLog.check(Build.VERSION.SDK_INT, new.size))
        new.forEach { DebugLog.i(AppLifeLog.EXIT, AppLifeLog.exit(exitOf(it))) }
        new.lastOrNull()?.let { prefs.edit().putLong(KeyLoggedUntil, it.timestamp).apply() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitOf(e: ApplicationExitInfo) = ProcessExit(
        e.timestamp, e.pid, e.processName.orEmpty(), reasonName(e.reason), e.importance, e.description.orEmpty(),
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> ProcessExit.ANR
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        else -> "REASON_$reason" // Rarer ones by number, as ApplicationExitInfo numbers them.
    }

    private const val PrefsFile = "process_exits"
    private const val KeyLoggedUntil = "logged_until"
}
