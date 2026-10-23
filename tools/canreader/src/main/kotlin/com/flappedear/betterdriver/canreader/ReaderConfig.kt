package com.flappedear.betterdriver.canreader

import com.flappedear.betterdriver.obd.CarProfiles
import com.flappedear.betterdriver.obd.PidChannel
import com.flappedear.betterdriver.obd.StandardPids

/**
 * What the reader asks the car (FBBD-319). [pidReads] is how often each request is repeated to
 * measure its rate. [surveyMs] is how long the whole bus is monitored, [listenMs] how long each id
 * is listened to on its own (at most [maxListenIds] ids). [sweepHeaders] are the ECUs whose mode 21
 * local identifiers 00..FF are all asked. [wishIds] are the CAN ids of Arek's FR-S wish list, from
 * the FT86 gen 1 CAN map (https://github.com/timurrrr/ft86/blob/main/can_bus/gen1.md): 0D0
 * steering angle, 0D1 speed and brake pressure, 140 accelerator and rpm, 144 brake pedal flag, 360
 * oil and coolant temperature. The drive log listens to each for [driveListenMs] in turn and reads
 * the [wishList] requests every [driveDiagEveryMs].
 */
data class ReaderConfig(
    val pidReads: Int = 10,
    val surveyMs: Long = 5_000,
    val listenMs: Long = 1_500,
    val maxListenIds: Int = 40,
    val sweepHeaders: List<Int> = FrsSweepHeaders,
    val wishIds: List<Int> = FrsWishIds,
    val driveListenMs: Long = 250,
    val driveDiagEveryMs: Long = 2_000,
    val wishList: List<PidChannel> = FrsWishList,
)

/** Engine, transmission, ABS/VSC, combination meter, A/C and the 7D0 ECU (Toyota diagnostic request ids). */
val FrsSweepHeaders: List<Int> = listOf(0x7E0, 0x7E1, 0x7B0, 0x7C0, 0x7C4, 0x7D0)

/** The wish list's CAN ids on a gen 1 FR-S (see [ReaderConfig]). */
val FrsWishIds: List<Int> = listOf(0x0D0, 0x0D1, 0x140, 0x144, 0x360)

/**
 * Arek's FR-S wish list as OBD-II requests (FBBD-263): the FR-S profile of the main app (brake
 * 7B0 21 41, accelerator 01 5A, oil 7E0 21 01, ATF 7E1 21 82) and the standard PIDs for the same
 * values (coolant, intake air, oil, accelerator pedal D, throttle), so the log shows which of them
 * the car answers and how fast.
 */
val FrsWishList: List<PidChannel> = CarProfiles.ScionFrs2013.channels + listOf(
    StandardPids.Coolant,
    StandardPids.IntakeAirTemp,
    StandardPids.OilTemp.copy(id = "oil_temp_01"),
    StandardPids.PedalD,
    StandardPids.Throttle,
)
