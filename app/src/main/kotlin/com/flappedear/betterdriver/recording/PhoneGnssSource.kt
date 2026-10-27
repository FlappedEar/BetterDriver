package com.flappedear.betterdriver.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.PhoneFix
import com.flappedear.betterdriver.recorder.PhoneGnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.phoneFixMicros
import com.flappedear.betterdriver.recorder.phoneFixSet
import com.flappedear.betterdriver.recorder.log.DebugLog

/**
 * The phone's GPS as a recorder [DataSource] (FBBD-275): Android's GPS provider at the fastest rate
 * it gives (typically 1 Hz), with the satellites used in the fix. Without location permission or a
 * GPS it records nothing and says so in the log; the session goes on with its other sources.
 */
class PhoneGnssSource(private val context: Context) : DataSource {
    override val name = Provenance.PhoneGnssDevice

    private var thread: HandlerThread? = null
    private var listener: LocationListener? = null
    private var statusCallback: GnssStatus.Callback? = null

    @Volatile private var satellites: Int? = null

    @Volatile private var lastFixNanos = 0L

    @SuppressLint("MissingPermission") // Checked first.
    override fun start(sink: SampleSink) {
        val manager = context.getSystemService(LocationManager::class.java)
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (manager == null || !manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            DebugLog.i(Tag, "no GPS on this phone")
            return
        }
        if (!granted) {
            DebugLog.i(Tag, "precise location not allowed: phone GPS records nothing")
            return
        }
        PhoneGnssChannels.ALL.forEach(sink::defineChannel)
        val worker = HandlerThread("phone-gnss").apply { start() }
        val handler = Handler(worker.looper)
        val l = LocationListener { location ->
            lastFixNanos = SystemClock.elapsedRealtimeNanos()
            sink.submit(phoneFixSet(stamp(sink, location), location.toFix()))
        }
        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                satellites = (0 until s.satelliteCount).count(s::usedInFix)
            }
        }
        thread = worker
        listener = l
        statusCallback = status
        lastFixNanos = SystemClock.elapsedRealtimeNanos()
        register(manager, handler)
        handler.postDelayed(object : Runnable {
            override fun run() {
                // A restart in the background may get no location (Codex on #295): asked again until it does.
                if (SystemClock.elapsedRealtimeNanos() - lastFixNanos > StaleNanos) register(manager, handler)
                handler.postDelayed(this, RetryMillis)
            }
        }, RetryMillis)
    }

    @SuppressLint("MissingPermission") // Checked in start; a later refusal is caught.
    @Synchronized // Never after stop has removed it.
    private fun register(manager: LocationManager, handler: Handler) {
        val l = listener ?: return
        val status = statusCallback ?: return
        try {
            manager.removeUpdates(l)
            manager.unregisterGnssStatusCallback(status)
            manager.registerGnssStatusCallback(status, handler)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, l, handler.looper)
            DebugLog.i(Tag, "recording, GPS enabled: ${manager.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
        } catch (e: SecurityException) {
            DebugLog.i(Tag, "refused: ${e.message}")
        }
    }

    private fun stamp(sink: SampleSink, location: Location): Long =
        phoneFixMicros(sink.nowMicros(), SystemClock.elapsedRealtimeNanos(), location.elapsedRealtimeNanos)

    private fun Location.toFix() = PhoneFix(
        lat = latitude,
        lon = longitude,
        utcMillis = time,
        speedMs = if (hasSpeed()) speed.toDouble() else null,
        bearingDeg = if (hasBearing()) bearing.toDouble() else null,
        altitudeM = if (hasAltitude()) altitude else null,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
        satellites = satellites ?: satellitesExtra(),
    )

    @Suppress("DEPRECATION") // The extra is the only place some phones put it; the callback is preferred.
    private fun Location.satellitesExtra(): Int? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        extras?.getInt("satellites", -1)?.takeIf { it >= 0 }
    } else {
        null
    }

    @Synchronized
    override fun stop() {
        val manager = context.getSystemService(LocationManager::class.java)
        listener?.let { manager?.removeUpdates(it) }
        statusCallback?.let { manager?.unregisterGnssStatusCallback(it) }
        listener = null
        statusCallback = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        const val Tag = "PhoneGnss"

        /** Without a position for this long, location updates are asked for again. */
        const val RetryMillis = 10_000L
        const val StaleNanos = RetryMillis * 1_000_000L
    }
}
