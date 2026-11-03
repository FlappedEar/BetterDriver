package com.flappedear.betterdriver.recording

import android.os.Bundle
import com.flappedear.betterdriver.core.ble.hr.HeartRateSource
import com.flappedear.betterdriver.core.ble.obd.ObdSource
import com.flappedear.betterdriver.core.ble.tpms.TpmsSource

/**
 * Adds the recording's OBD-II reader (FBBD-261), heart-rate strap (FBBD-268) and TPMS bridge
 * (FBBD-444) to a [RecorderProtocol.MSG_STATE] bundle, for the track screen's status strip.
 */
internal fun putSensorStatus(state: Bundle) {
    ObdSource.current?.status()?.let { RecorderProtocol.putObd(state, it) }
    HeartRateSource.current?.status()?.let { RecorderProtocol.putHeartRate(state, it) }
    TpmsSource.current?.status()?.let {
        state.putString(RecorderProtocol.KEY_TYRES, it.value)
        state.putBoolean(RecorderProtocol.KEY_TYRES_OK, it.ok)
    }
}
