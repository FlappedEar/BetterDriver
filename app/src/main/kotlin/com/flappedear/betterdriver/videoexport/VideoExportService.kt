package com.flappedear.betterdriver.videoexport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.R
import com.flappedear.betterdriver.recorder.log.DebugLog

/**
 * Keeps the app running while [VideoExports] renders a video with data (FBBD-152), which takes
 * minutes, so it goes on with the screen off or another app in front. It does no work itself: the
 * export runs in [VideoExports], in this (the UI) process, never in the recorder's. Started and
 * stopped by [VideoExports]; its notification says a video is being made.
 */
class VideoExportService : Service() {
    /**
     * A foreground service alone does not keep the CPU running with the screen off, and the render
     * would stall: a partial wake lock is held from start to stop. Timed, so a lost stop cannot keep
     * the phone awake for ever.
     */
    private val cpu by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BetterDriver:video-export")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            releaseCpu()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            cpu.acquire(WakeLockTimeoutMs)
            goForeground()
        }
        // Not restarted by the system: the export it kept alive is gone with the process.
        return START_NOT_STICKY
    }

    /**
     * Android 15+ lets media processing run 6 h a day. When that is used up it calls this, and ends
     * the app unless the service stops within seconds: the export stops with it.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DebugLog.w(Tag, "out of time for media processing today: the video export stops")
        VideoExports.get(this).stopForTimeout()
        releaseCpu()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseCpu()
        super.onDestroy()
    }

    private fun releaseCpu() {
        if (cpu.isHeld) cpu.release()
    }

    @Suppress("TooGenericExceptionCaught") // Not allowed to start from the background: the export still runs.
    private fun goForeground() {
        try {
            val channel = NotificationChannel(
                ChannelId,
                getString(R.string.video_export_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val type = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            }
            ServiceCompat.startForeground(this, NotificationId, notification(), type)
        } catch (e: RuntimeException) {
            DebugLog.w(Tag, "could not keep the video export in the foreground", e)
            releaseCpu()
            stopSelf()
        }
    }

    private fun notification() = NotificationCompat.Builder(this, ChannelId)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(getString(R.string.video_export_title))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()

    companion object {
        private const val Tag = "VideoExportService"
        private const val NotificationId = 3
        private const val ChannelId = "video_export"

        /** Longer than any render; Android's own limit for a media-processing service is 6 h. */
        private const val WakeLockTimeoutMs = 6 * 60 * 60 * 1_000L
        private const val ActionStop = "com.flappedear.betterdriver.videoexport.STOP"

        /** Main thread, while the app is in front (the user just picked where to save). */
        @Suppress("TooGenericExceptionCaught") // Refused from the background on newer Android.
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, VideoExportService::class.java))
            } catch (e: RuntimeException) {
                DebugLog.w(Tag, "could not start the video export service", e)
            }
        }

        /**
         * Stops it through its own queue, after the start: stopping a service that was started in the
         * foreground before it has called startForeground can crash the app.
         */
        @Suppress("TooGenericExceptionCaught") // Refused from the background: then stop it directly.
        fun stop(context: Context) {
            val intent = Intent(context, VideoExportService::class.java)
            try {
                context.startService(intent.setAction(ActionStop))
            } catch (e: RuntimeException) {
                DebugLog.w(Tag, "stopping the video export service directly", e)
                context.stopService(intent)
            }
        }
    }
}
