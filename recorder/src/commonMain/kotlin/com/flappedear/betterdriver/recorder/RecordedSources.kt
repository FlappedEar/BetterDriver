package com.flappedear.betterdriver.recorder

/**
 * The devices an iPhone session lists in its metadata (FBBD-436), as an Android session does with
 * its sources' names: the VBO's comment lines, the session check and the provenance of sessions
 * stored without one read this list.
 *
 * The receiver's own name comes first (the log's start line and the RaceBox Micro check read it),
 * then the name Android gives its receiver kind when there is one, then each other source the session
 * starts: the phone's accelerometer, OBD-II, a heart-rate strap and the tyre pressure bridge.
 * As on Android, a source is listed when the session starts it, whether or not it connects.
 */
object RecordedSources {
    /** Android's PhoneImuSource. */
    const val PhoneImu = "phone_imu"

    /** Android's ObdSource, and the OBD-II channels' source. */
    const val Obd = "obd"

    /** Android's HeartRateSource (HeartRateDecoder.SOURCE). */
    const val HeartRate = "heart_rate"

    /** Android's TpmsSource (TpmsChannels.SOURCE). */
    const val Tyres = "tpms"

    /** Android's RaceBoxSource (RaceBoxDecoder.SOURCE). */
    const val RaceBox = "racebox"

    @Suppress("LongParameterList") // One flag per source a session can start.
    fun list(
        receiverName: String,
        receiver: RecordedReceiver,
        phoneImu: Boolean,
        obd: Boolean,
        heartRate: Boolean,
        tyres: Boolean,
    ): List<String> = buildList {
        add(receiverName)
        when (receiver) {
            RecordedReceiver.RACEBOX -> add(RaceBox)
            RecordedReceiver.PHONE -> add(Provenance.PhoneGnssDevice)
            RecordedReceiver.MADE_UP -> add(Provenance.DemoDevice)
            RecordedReceiver.OTHER -> Unit
        }
        if (phoneImu) add(PhoneImu)
        if (obd) add(Obd)
        if (heartRate) add(HeartRate)
        if (tyres) add(Tyres)
    }.distinct()
}

/** The kind of receiver a session records from, for the name Android gives that kind ([RecordedSources]). */
enum class RecordedReceiver { RACEBOX, PHONE, MADE_UP, OTHER }
