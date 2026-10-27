package com.flappedear.betterdriver.analysis.data

import com.flappedear.betterdriver.recorder.Provenance

/** How each provenance is written in the report. */
internal fun provenanceText(p: Provenance): String = when (p) {
    Provenance.MEASURED_GPS -> "measured by GPS"
    Provenance.MEASURED_GNSS_RECEIVER -> "measured by the GNSS receiver"
    Provenance.MEASURED_ACCELEROMETER -> "measured by the accelerometer"
    Provenance.MEASURED_OBD -> "measured through OBD"
    Provenance.MEASURED_CAN -> "measured on the CAN bus"
    Provenance.MEASURED_SENSOR -> "measured by a sensor"
    Provenance.CALCULATED -> "calculated"
    Provenance.INFERRED -> "inferred"
    Provenance.FUSED -> "fused from several sources"
    Provenance.INTERPOLATED -> "interpolated"
    Provenance.SIMULATED -> "simulated"
    Provenance.UNKNOWN -> "of unknown source"
}
