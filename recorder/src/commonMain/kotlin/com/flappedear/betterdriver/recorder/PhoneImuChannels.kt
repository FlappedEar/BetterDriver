package com.flappedear.betterdriver.recorder

/**
 * The phone's own accelerometer (FBBD-218), in g along the phone's axes with gravity included like
 * the RaceBox's, on Android's axes: X to the screen's right, Y to its top, Z out of the screen, so a
 * phone lying face up reads Z +1. Recorded in every session next to the receiver; with phone GPS only
 * it gives the live G (FBBD-337).
 */
object PhoneImuChannels {
    const val SOURCE = PhoneGnssChannels.SOURCE
    val ACCEL_X = ChannelDef(700, "phone_accel_x", "g", SOURCE, 3)
    val ACCEL_Y = ChannelDef(701, "phone_accel_y", "g", SOURCE, 3)
    val ACCEL_Z = ChannelDef(702, "phone_accel_z", "g", SOURCE, 3)
    val ALL = listOf(ACCEL_X, ACCEL_Y, ACCEL_Z)
}

/** One accelerometer reading [x], [y], [z] in g on Android's axes, as the set the recorder stores at [tMicros]. */
fun phoneAccelSet(tMicros: Long, x: Double, y: Double, z: Double): SampleSet = SampleSet(
    tMicros,
    listOf(
        ChannelValue(PhoneImuChannels.ACCEL_X.id, x),
        ChannelValue(PhoneImuChannels.ACCEL_Y.id, y),
        ChannelValue(PhoneImuChannels.ACCEL_Z.id, z),
    ),
)

/** A reading is measured within this of its delivery; an older stamp is on an unexpected clock. */
private const val MaxImuAgeMicros = 1_000_000L

/**
 * The time on the recorder's clock a reading [ageMicros] old at [nowMicros] was measured; delivery time
 * if the age is implausible.
 */
fun phoneImuMicrosByAge(nowMicros: Long, ageMicros: Long): Long =
    if (ageMicros in 0..MaxImuAgeMicros) nowMicros - ageMicros else nowMicros
