package com.flappedear.betterdriver.recorder

/**
 * Where a channel's values come from (FBBD-174). Kept per channel, not per sample: every value of a
 * channel shares its provenance. Stored in the session's metadata ([store.SessionFiles]); a session
 * written before provenance was stored gets one derived from its source on load ([Provenance.derive]).
 *
 * The rule that follows from it: a value whose provenance is not [measured] is never shown as
 * measured, and no precise advice is given from it.
 */
enum class Provenance(val measured: Boolean) {
    /** A GPS fix from a receiver that is not known: a phone's location, or a file that does not say. */
    MEASURED_GPS(true),

    /** A dedicated GNSS receiver: RaceBox, VBOX, an NMEA, LNS, RaceChrono DIY or Dragy receiver. */
    MEASURED_GNSS_RECEIVER(true),

    /** An accelerometer or gyroscope (an IMU), such as the RaceBox's. */
    MEASURED_ACCELEROMETER(true),

    /** Read from the car's OBD-II port. */
    MEASURED_OBD(true),

    /** Read from the car's CAN bus. */
    MEASURED_CAN(true),

    /** Another sensor: a heart-rate strap, a receiver's supply voltage. */
    MEASURED_SENSOR(true),

    /** Worked out from measured values by a known formula: speed from consecutive positions, a theoretical best. */
    CALCULATED(false),

    /** Estimated where the thing itself was not observed: a braking point from deceleration, no brake pedal. */
    INFERRED(false),

    /** Combined from several sources, such as GNSS and IMU blended by a filter. */
    FUSED(false),

    /** Filled in between samples. */
    INTERPOLATED(false),

    /** Made up: the positions of Demo GNSS. */
    SIMULATED(false),

    /** Imported from a file that does not say where the values came from. */
    UNKNOWN(false),
    ;

    companion object {
        /** The provenance stored as [name], or null for a name this version does not know. */
        fun parse(name: String?): Provenance? = entries.firstOrNull { it.name == name }

        /**
         * The provenance of a channel from its [source] and [name] alone, as every source in this
         * app defines its channels: a receiver's channels are measured by the receiver, its motion
         * sensor's by the accelerometer. The default of [ChannelDef.provenance], and what a session
         * stored before FBBD-174 is read with ([forSession] refines it with what the session says).
         */
        @Suppress("CyclomaticComplexMethod") // One branch per source this app records.
        fun derive(source: String, name: String): Provenance = when (source) {
            GnssChannels.SOURCE -> MEASURED_GNSS_RECEIVER
            "racebox", "nmea", "racechrono_diy", "vbox_sport", "dragy", "lns" -> when {
                isMotion(name) -> MEASURED_ACCELEROMETER
                name == "racebox_input" -> MEASURED_SENSOR
                // The receiver adds up its own positions: not a measurement of distance.
                name == "lns_total_distance" -> CALCULATED
                else -> MEASURED_GNSS_RECEIVER
            }
            "heart_rate" -> MEASURED_SENSOR
            // The phone's own accelerometer (FBBD-218).
            "phone" -> if (isMotion(name)) MEASURED_ACCELEROMETER else MEASURED_SENSOR
            "obd" -> MEASURED_OBD
            "can" -> MEASURED_CAN
            // An import's acceleration: VBOX and RaceChrono files usually work it out from GPS speed.
            ImportSource -> if (isMotion(name)) CALCULATED else UNKNOWN
            else -> UNKNOWN
        }

        /**
         * [def]'s provenance in a session stored without one (before FBBD-174), from what its metadata
         * says: Demo GNSS positions are simulated; an import's GNSS channels are a GPS of unknown kind,
         * a VBO file's a GNSS receiver's, and a GPX file's speed and heading were mostly worked out
         * from its positions ([ImportOrigin.format]).
         */
        fun forSession(def: ChannelDef, devices: List<String>, imported: ImportOrigin?): Provenance {
            val derived = derive(def.source, def.name)
            if (def.source != GnssChannels.SOURCE) return derived
            return when {
                imported == null -> if (DemoDevice in devices) SIMULATED else derived
                imported.format == "VBO" -> MEASURED_GNSS_RECEIVER
                imported.format == "GPX" && def.id in GpsDerived -> CALCULATED
                else -> MEASURED_GPS
            }
        }

        /** Name of the Demo GNSS source ([fake.FakeGnssSource]) as a session lists it in its devices. */
        const val DemoDevice = "fake-gnss"

        /** Source of the channels an import adds (transfer's SessionImport.Source). */
        const val ImportSource = "import"

        // A getter: GnssChannels makes ChannelDefs, whose default provenance initialises this object.
        private val GpsDerived get() = setOf(GnssChannels.SPEED_KMH.id, GnssChannels.HEADING.id)

        private fun isMotion(name: String) =
            name.contains("accel") || name.contains("gyro")
    }
}

