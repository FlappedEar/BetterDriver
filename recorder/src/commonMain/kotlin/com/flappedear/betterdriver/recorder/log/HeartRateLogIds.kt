package com.flappedear.betterdriver.recorder.log

/** What the heart-rate check needs to know of core-ble's heart rate source (FBBD-119), which this module cannot see. */
object HeartRateLogIds {
    /** core-ble's HeartRateChannels.HEART_RATE. */
    const val CHANNEL = 400

    /** That channel's name, as the session, the VBO export and the analysis know it. */
    const val NAME = "heart_rate"

    /** core-ble's HeartRateEvents.DEVICE: "address=<address> name=<name>", once at start. */
    const val DEVICE = "hr_device"

    /** core-ble's HeartRateEvents.DISCONNECTED: the link to the monitor dropped. */
    const val DISCONNECTED = "hr_disconnected"

    /** core-ble's HeartRateEvents.CONTACT: "detected" or "lost". */
    const val CONTACT = "hr_contact"
    const val CONTACT_LOST = "lost"

    /** core-ble's HeartRateEvents.IMPLAUSIBLE: a heart rate outside what a driver can have, left out. */
    const val IMPLAUSIBLE = "hr_implausible"

    /** core-ble's HeartRateEvents.OFF: no plausible heart rate early in the session, monitor off. */
    const val OFF = "hr_off"
}
