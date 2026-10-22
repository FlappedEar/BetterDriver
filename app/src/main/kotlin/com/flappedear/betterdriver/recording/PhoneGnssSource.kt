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
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.log.DebugLog

/**
 * The phone's own GPS as the session's position source (FBBD-275), for when there is no receiver.
 * Its positions go into the receiver channels like any receiver's, but marked as a phone's GPS
 * ([Provenance.MEASURED_GPS]), never as a GNSS receiver's, so analysis and the coach treat them as
 * the weaker evidence they are; at about one position a second they also grade themselves down.
 */
object PhoneGnssChannels {
    /** The receiver channels with a phone's provenance. */
    val GNSS = GnssChannels.ALL.map { it.copy(provenance = Provenance.MEASURED_GPS) }

    /** Android's estimate of the position's accuracy, the radius of 68 % confidence. */
    val ACCURACY = ChannelDef(710, "phone_gps_accuracy", "m", PhoneImuChannels.SOURCE, 1, Provenance.MEASURED_GPS)

    val ALL = GNSS + ACCURACY
}

/** One position as Android reports it, without the Android types, so it can be checked off the phone. */
data class PhoneFix(
    val lat: Double,
    val lon: Double,
    val utcMillis: Long,
    val speedMs: Double? = null,
    val bearingDeg: Double? = null,
    val altitudeM: Double? = null,
    val accuracyM: Double? = null,
    val satellites: Int? = null,
)

private const val KmhPerMs = 3.6

/** [fix] as the set the recorder stores at [tMicros]. */
fun phoneFixSet(tMicros: Long, fix: PhoneFix): SampleSet = SampleSet(
    tMicros,
    buildList {
        add(ChannelValue(GnssChannels.UTC_MS.id, fix.utcMillis.toDouble()))
        add(ChannelValue(GnssChannels.LAT.id, fix.lat))
        add(ChannelValue(GnssChannels.LON.id, fix.lon))
        fix.speedMs?.let { add(ChannelValue(GnssChannels.SPEED_KMH.id, it * KmhPerMs)) }
        fix.bearingDeg?.let { add(ChannelValue(GnssChannels.HEADING.id, it)) }
        fix.altitudeM?.let { add(ChannelValue(GnssChannels.ALT_M.id, it)) }
        fix.satellites?.let { add(ChannelValue(GnssChannels.SATS.id, it.toDouble())) }
        fix.accuracyM?.let { add(ChannelValue(PhoneGnssChannels.ACCURACY.id, it)) }
    },
)

/**
 * The time on the recorder's clock a position was measured at: [nowMicros] (delivery) less its age,
 * the boot clock now ([bootNanos]) less the boot time Android stamped it with ([fixBootNanos]). A
 * negative or implausible age (over [MaxAgeMicros]) means the stamp cannot be trusted: delivery time.
 */
fun phoneFixMicros(nowMicros: Long, bootNanos: Long, fixBootNanos: Long): Long {
    val age = (bootNanos - fixBootNanos) / NanosPerMicro
    return if (age in 0..MaxAgeMicros) nowMicros - age else nowMicros
}

private const val NanosPerMicro = 1_000L

/** Android delivers a position within about a second; anything older has an unexpected stamp. */
private const val MaxAgeMicros = 2_000_000L

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
        val l = LocationListener { location -> sink.submit(phoneFixSet(stamp(sink, location), location.toFix())) }
        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                satellites = (0 until s.satelliteCount).count(s::usedInFix)
            }
        }
        try {
            manager.registerGnssStatusCallback(status, handler)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, l, worker.looper)
            DebugLog.i(Tag, "recording, GPS enabled: ${manager.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
        } catch (e: SecurityException) {
            DebugLog.i(Tag, "refused: ${e.message}")
        }
        thread = worker
        listener = l
        statusCallback = status
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
    }
}
