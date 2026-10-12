package com.flappedear.betterdriver.obd

/**
 * The generic mode 01 channels (FBBD-53) every car gets, asked with the default header. Which of
 * them a car has comes from its supported-PID bitmasks ([SupportedPids]).
 *
 * Formulas are SAE J1979 (ISO 15031-5) service 01 scalings, as tabulated in
 * https://en.wikipedia.org/wiki/OBD-II_PIDs#Service_01. They are written with a floating-point
 * constant (`100.0`, `4.0`) wherever J1979 divides, because an all-integer RaceChrono equation
 * divides in integers (see [com.flappedear.betterdriver.obd.equation.Equation]).
 */
object StandardPids {
    /** 0x04: calculated engine load, A·100/255. */
    val EngineLoad = mode01("engine_load", "Engine load", 0x04, "A*100.0/255", "%", PollClass.FAST)

    /** 0x05: engine coolant temperature, A−40. */
    val Coolant = mode01("coolant_temp", "Coolant temp", 0x05, "A-40", "°C", PollClass.SLOW)

    /** 0x0B: intake manifold absolute pressure, A. */
    val ManifoldPressure = mode01("map", "Manifold pressure", 0x0B, "A", "kPa", PollClass.FAST)

    /** 0x0C: engine speed, (256A+B)/4. */
    val Rpm = mode01("rpm", "RPM", 0x0C, "(A*256+B)/4.0", "rpm", PollClass.FAST)

    /** 0x0D: vehicle speed, A. */
    val Speed = mode01("speed", "Speed", 0x0D, "A", "km/h", PollClass.FAST)

    /** 0x0E: timing advance before TDC, A/2−64. */
    val TimingAdvance = mode01("timing_advance", "Timing advance", 0x0E, "A/2.0-64", "°", PollClass.FAST)

    /** 0x0F: intake air temperature, A−40. */
    val IntakeAirTemp = mode01("intake_air_temp", "Intake air temp", 0x0F, "A-40", "°C", PollClass.SLOW)

    /** 0x10: mass air flow, (256A+B)/100. */
    val Maf = mode01("maf", "Mass air flow", 0x10, "(A*256+B)/100.0", "g/s", PollClass.FAST)

    /** 0x11: throttle position, A·100/255. */
    val Throttle = mode01("throttle", "Throttle", 0x11, "A*100.0/255", "%", PollClass.FAST)

    /** 0x24: oxygen sensor 1 (wide band) equivalence ratio λ, 2/65536·(256A+B); C, D are its voltage. */
    val Lambda = mode01("lambda", "Lambda", 0x24, "(A*256+B)*2.0/65536", "λ", PollClass.FAST)

    /** 0x2F: fuel tank level input, A·100/255. */
    val FuelLevel = mode01("fuel_level", "Fuel level", 0x2F, "A*100.0/255", "%", PollClass.SLOW)

    /** 0x42: control module voltage, (256A+B)/1000; the battery voltage seen by the ECU. */
    val ModuleVoltage = mode01("module_voltage", "Battery voltage", 0x42, "(A*256+B)/1000.0", "V", PollClass.SLOW)

    /** 0x44: commanded air-fuel equivalence ratio λ, 2/65536·(256A+B). */
    val CommandedLambda =
        mode01("commanded_lambda", "Commanded lambda", 0x44, "(A*256+B)*2.0/65536", "λ", PollClass.FAST)

    /** 0x46: ambient air temperature, A−40. */
    val AmbientTemp = mode01("ambient_temp", "Ambient temp", 0x46, "A-40", "°C", PollClass.SLOW)

    /** 0x49: accelerator pedal position D, A·100/255. */
    val PedalD = mode01("pedal_d", "Accelerator pedal D", 0x49, "A*100.0/255", "%", PollClass.FAST)

    /** 0x4A: accelerator pedal position E, A·100/255. */
    val PedalE = mode01("pedal_e", "Accelerator pedal E", 0x4A, "A*100.0/255", "%", PollClass.FAST)

    /** 0x5A: relative accelerator pedal position, A·100/255. */
    val Accelerator = mode01("accelerator", "Accelerator", 0x5A, "A*100.0/255", "%", PollClass.FAST)

    /** 0x5C: engine oil temperature, A−40. */
    val OilTemp = mode01("oil_temp", "Oil temp", 0x5C, "A-40", "°C", PollClass.SLOW)

    /** All of them, in PID order. */
    val all: List<PidChannel> = listOf(
        EngineLoad, Coolant, ManifoldPressure, Rpm, Speed, TimingAdvance, IntakeAirTemp, Maf, Throttle, Lambda,
        FuelLevel, ModuleVoltage, CommandedLambda, AmbientTemp, PedalD, PedalE, Accelerator, OilTemp,
    )

    @Suppress("LongParameterList")
    private fun mode01(id: String, name: String, pid: Int, equation: String, unit: String, poll: PollClass) =
        PidChannel(id, name, header = null, mode = 0x01, pid = pid, equation = equation, unit = unit, pollClass = poll)
}
