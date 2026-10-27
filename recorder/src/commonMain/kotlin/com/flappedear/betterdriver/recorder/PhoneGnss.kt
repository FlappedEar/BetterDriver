package com.flappedear.betterdriver.recorder

/**
 * The phone's own GPS as the session's position source (FBBD-275), for when there is no receiver.
 * Its positions go into the receiver channels like any receiver's, but marked as a phone's GPS
 * ([Provenance.MEASURED_GPS]), never as a GNSS receiver's, so analysis and the coach treat them as
 * the weaker evidence they are; at about one position a second they also grade themselves down.
 */
object PhoneGnssChannels {
    /** The phone's own sensors' source name, as the Android app's PhoneImuChannels.SOURCE. */
    const val SOURCE = "phone"

    /** The receiver channels with a phone's provenance. */
    val GNSS = GnssChannels.ALL.map { it.copy(provenance = Provenance.MEASURED_GPS) }

    /** The phone's estimate of the position's accuracy, the radius of 68 % confidence. */
    val ACCURACY = ChannelDef(710, "phone_gps_accuracy", "m", SOURCE, 1, Provenance.MEASURED_GPS)

    val ALL = GNSS + ACCURACY
}

/** One position as the phone reports it, without Android or iOS types, so it can be checked off the phone. */
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
fun phoneFixMicros(nowMicros: Long, bootNanos: Long, fixBootNanos: Long): Long =
    phoneFixMicrosByAge(nowMicros, (bootNanos - fixBootNanos) / NanosPerMicro)

/**
 * As [phoneFixMicros] for a position [ageMicros] old at [nowMicros]; iOS gives a position's age from
 * its timestamp. A negative or implausible age means delivery time.
 */
fun phoneFixMicrosByAge(nowMicros: Long, ageMicros: Long): Long =
    if (ageMicros in 0..MaxAgeMicros) nowMicros - ageMicros else nowMicros

private const val NanosPerMicro = 1_000L

/** A phone delivers a position within about a second; anything older has an unexpected stamp. */
private const val MaxAgeMicros = 2_000_000L
