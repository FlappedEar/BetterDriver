package com.flappedear.betterdriver.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import com.flappedear.betterdriver.MainActivity
import com.flappedear.betterdriver.R

private const val ChannelId = "recording"
private const val TickMs = 250L

/** Longer than any outing; the lock is released when the recording stops. */
private const val WakeLockTimeoutMs = 8 * 60 * 60 * 1_000L

internal fun createRecordingChannel(context: Context) {
    val channel = NotificationChannel(
        ChannelId,
        context.getString(R.string.recording_channel),
        NotificationManager.IMPORTANCE_LOW,
    )
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/** The ongoing notification Android requires for a foreground service: a title and a running clock. */
internal fun recordingNotification(context: Context, startMillis: Long): Notification {
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, ChannelId)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(context.getString(R.string.recording_title))
        .setWhen(startMillis)
        .setUsesChronometer(true)
        .setOngoing(true)
        .setContentIntent(open)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}

/**
 * Keeps the CPU running while recording. The UI keeps the screen on, but if the UI dies the phone
 * may go to sleep, and the recorder must keep going.
 */
internal class CpuLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BetterDriver:recorder")
        .apply { setReferenceCounted(false) }

    fun acquire() = lock.acquire(WakeLockTimeoutMs)

    fun release() {
        if (lock.isHeld) lock.release()
    }
}

/**
 * The UI processes bound to the service. Each registered client gets [state] every [TickMs] while
 * any client is registered. Main thread only.
 */
internal class LiveClients(private val state: () -> Bundle) {
    private val main = Handler(Looper.getMainLooper())
    private val clients = mutableListOf<Messenger>()
    private val ticker = object : Runnable {
        override fun run() {
            broadcast()
            if (clients.isNotEmpty()) main.postDelayed(this, TickMs)
        }
    }

    val messenger = Messenger(Handler(Looper.getMainLooper(), ::onMessage))

    private fun onMessage(msg: Message): Boolean {
        val client = msg.replyTo
        return when {
            client == null -> false
            msg.what == RecorderProtocol.MSG_REGISTER -> {
                clients += client
                main.removeCallbacks(ticker)
                ticker.run()
                true
            }
            msg.what == RecorderProtocol.MSG_UNREGISTER -> {
                clients -= client
                true
            }
            else -> false
        }
    }

    fun broadcast() {
        val bundle = state()
        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                client.send(Message.obtain(null, RecorderProtocol.MSG_STATE).apply { data = bundle })
            } catch (_: RemoteException) {
                iterator.remove() // That client's process is gone.
            }
        }
    }

    fun close() = main.removeCallbacks(ticker)
}
