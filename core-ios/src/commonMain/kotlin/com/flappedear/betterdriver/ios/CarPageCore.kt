package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.obd.CanBroadcasts
import com.flappedear.betterdriver.obd.PollSet
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.obd.SupportedPids
import com.flappedear.betterdriver.obd.equation.Equation
import com.flappedear.betterdriver.setup.CanIdRecord
import com.flappedear.betterdriver.setup.CarParameter
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.CarScanRecord
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.setup.TileIds

/**
 * What the iPhone's car page says and changes (FBBD-344), as the Android app's CarParameters.kt and
 * CarRoutes.kt: the driver's own parameters, the PIDs and CAN ids of the last scan. Cars are passed
 * by id: setup's and obd's CarProfile share a name, so Swift sees one of them renamed.
 */
@Suppress("TooManyFunctions") // The car page's texts and changes, each small.
object CarPageCore {
    /** Where [p] comes from, as one line: "CAN 0D4 · bytesToUIntLe(raw, 4, 2)" or "OBD 7E0 01 06 · A-40". */
    fun parameterSource(p: CarParameter): String = when (p.source) {
        CarParameterSource.CAN -> "CAN ${hex3(p.canId)} · ${p.equation}"
        CarParameterSource.OBD -> {
            val header = p.header?.let { "${it.toString(Hex).uppercase()} " }.orEmpty()
            val pid = p.pid.toString(Hex).uppercase().padStart(2 * p.pidLength, '0')
            "OBD $header${hex2(p.mode)} $pid · ${p.equation}"
        }
    }

    /** The mode 01 PIDs a scan found, without the bitmask PIDs (01 00, 01 20 …) that only list the others. */
    fun reportedPids(scan: CarScanRecord): List<Int> = scan.supported.filter { it !in SupportedPids.bitmaskPids }

    /** Why a scan heard no CAN ids. */
    fun noCanReason(scan: CarScanRecord): String {
        val p = scan.protocol.trim().uppercase().removePrefix("A")
        return if (p == "6" || p == "8") "the adapter would not listen" else "the car is not on 11-bit CAN"
    }

    /** The bytes of an id that moved, as "bytes 0–1, 4 change", "byte 2 changes" or "no byte changed". */
    fun changingText(changing: List<Int>): String {
        if (changing.isEmpty()) return "no byte changed"
        val runs = ArrayList<IntRange>()
        for (i in changing.sorted()) {
            val last = runs.lastOrNull()
            if (last != null && last.last == i - 1) runs[runs.size - 1] = last.first..i else runs += i..i
        }
        val text = runs.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
        return if (changing.size == 1) "byte $text changes" else "bytes $text change"
    }

    /**
     * The names the app or the driver gives CAN id [id] on car [carId]: the driver's own parameters on
     * it, and the broadcasts the app reads on an FR-S (FBBD-320); empty for an id nobody reads.
     */
    fun canIdNames(setup: Setup, carId: String, id: Int): List<String> {
        val car = setup.car(carId) ?: return emptyList()
        val own = car.parameters.filter { it.source == CarParameterSource.CAN && it.canId == id }.map { it.name }
        val frs = readsFrsBroadcasts(car)
        val builtIn = if (frs) CanBroadcasts.Frs.filter { it.canId == id }.map { it.name } else emptyList()
        return (builtIn + own).distinct()
    }

    /** How often a CAN id was heard: "50/s", or "2.5/s" below ten a second. */
    fun rate(perSecond: Double): String =
        if (perSecond >= WHOLE_RATE_FROM) "${perSecond.toInt()}/s" else "${fixed(perSecond, 1)}/s"

    /** The standard channel the app has for mode 01 PID [pid], or null when it has no formula for it. */
    fun standardName(pid: Int): String? = StandardPids.all.firstOrNull { it.pid == pid }?.name

    /** "01 0C" for mode 01 PID [pid]. */
    fun pidCode(pid: Int): String = "01 ${hex2(pid)}"

    /** "0D4" for CAN id [id]. */
    fun canIdText(id: Int): String = hex3(id)

    /**
     * The PIDs a session with car [carId] records: the ones switched on, or before any switch what the
     * recorder picks of what the car reported (the pedals, RPM, temperatures and the tiles' channels).
     */
    @Suppress("ReturnCount") // No car; the switched ones; the recorder's pick.
    fun recordedPids(setup: Setup, carId: String): Set<Int> {
        val car = setup.car(carId) ?: return emptySet()
        car.recordedPids?.let { return it.toSet() }
        val supported = car.scan?.supported?.toSet().orEmpty()
        val screens = setup.trackScreensFor(car.id)
        val tiles = (screens.main + screens.second).filterNotNull()
            .filter { it.startsWith(TileIds.OBD_PREFIX) }
            .map { it.removePrefix(TileIds.OBD_PREFIX) }
        return PollSet.Driver.plus(tiles).standardPids(supported)
    }

    /** Switches PID [pid] of car [carId] on or off for its sessions (UI-301 B). */
    fun recordPid(setup: Setup, carId: String, pid: Int, on: Boolean): Setup {
        val current = recordedPids(setup, carId)
        return change(setup, carId) { it.recordPid(pid, on, current) }
    }

    /** The parameter of car [carId] stored as [key], or null. */
    fun parameter(setup: Setup, carId: String, key: String): CarParameter? =
        setup.car(carId)?.parameters?.firstOrNull { it.key == key }

    /** The CAN id's record in car [carId]'s last scan, or null. */
    fun scanFrame(setup: Setup, carId: String, canId: Int?): CanIdRecord? =
        canId?.let { id -> setup.car(carId)?.scan?.canIds?.firstOrNull { it.id == id } }

    /**
     * Saves [draft] to car [carId]: replacing the parameter stored as [key], or as a new one when [key]
     * is null. Unchanged while a step is not valid.
     */
    @Suppress("ReturnCount") // No car; a step not done; saved.
    fun saveParameter(setup: Setup, carId: String, key: String?, draft: ParameterDraft): Setup {
        val car = setup.car(carId) ?: return setup
        val saved = draft.toParameter(key ?: car.newParameterKey(draft.name)) ?: return setup
        return change(setup, carId) { it.withParameter(saved) }
    }

    fun removeParameter(setup: Setup, carId: String, key: String): Setup =
        change(setup, carId) { it.withoutParameter(key) }

    /** Saves [change] to car [carId], if it is still there. */
    private fun change(setup: Setup, carId: String, change: (CarProfile) -> CarProfile): Setup =
        setup.car(carId)?.let { setup.updateCar(change(it)) } ?: setup

    /** Whether the app reads the FR-S broadcasts on [car]: by its name, or the VIN its scan read. */
    private fun readsFrsBroadcasts(car: CarProfile): Boolean {
        val name = isBroadcastCar(car.name)
        val vin = car.scan?.vinPrefix?.uppercase() ?: return name
        return name || CanBroadcasts.VinPrefixes.any { vin.startsWith(it) }
    }

    /** A first-generation FR-S / 86 / BRZ by name, as the Android app's isBroadcastCar. */
    private fun isBroadcastCar(name: String): Boolean =
        BroadcastCarName.containsMatchIn(name) && !Gen2Name.containsMatchIn(name) &&
            Year.findAll(name).none { it.value.toInt() >= FIRST_GEN2_YEAR }

    private const val WHOLE_RATE_FROM = 10
    private const val FIRST_GEN2_YEAR = 2022
    private val Gen2Name = Regex("""\bGR ?86\b""", RegexOption.IGNORE_CASE)
    private val Year = Regex("""\b(19|20)\d\d\b""")
    private val BroadcastCarName =
        Regex("""\bFR-?S\b|\bGT ?86\b|\bFT ?86\b|\bToyota 86\b|\bBRZ\b""", RegexOption.IGNORE_CASE)
}

/**
 * A parameter being added or changed in the steps (UI-302 B), as typed, as the Android app's. [canId],
 * [header] and [request] are hex text: "0D4"; "7E0" or empty for every ECU; "01 06" or "22 F190".
 */
data class ParameterDraft(
    val source: CarParameterSource,
    val canId: String,
    val header: String,
    val request: String,
    val equation: String,
    val unit: String,
    val fast: Boolean,
    val name: String,
) {
    /** The CAN id, when it is a valid 11-bit one. */
    val canIdValue: Int? get() = parseHex(canId)?.takeIf { it in 0..Max11Bit }

    /** The header, null for every ECU; [headerValid] is false for text that is not one. */
    val headerValue: Int? get() = parseHex(header)
    val headerValid: Boolean get() = header.isBlank() || headerValue?.let { it in 0..Max29Bit } == true

    /** Mode, PID and its length in bytes, when [request] is two or three hex bytes. */
    private val requestValue: Triple<Int, Int, Int>? get() {
        val hex = request.replace(" ", "")
        if (hex.length != 4 && hex.length != 6 || hex.any { it.uppercaseChar() !in HexDigits }) return null
        val mode = hex.substring(0, 2).toInt(Hex)
        if (mode !in 1..MaxMode) return null
        return Triple(mode, hex.substring(2).toInt(Hex), (hex.length - 2) / 2)
    }

    /** Step 1 done: where the value comes from is valid. */
    val sourceValid: Boolean get() = when (source) {
        CarParameterSource.CAN -> canIdValue != null
        CarParameterSource.OBD -> headerValid && requestValue != null
    }

    /** Step 2 done: the formula parses. */
    val equationValid: Boolean get() = equation.isNotBlank() && Equation.parse(equation) != null

    /** The parameter under [key], or null while a step is not valid. */
    @Suppress("ReturnCount") // Each early return is a step not done.
    fun toParameter(key: String): CarParameter? {
        if (!sourceValid || !equationValid) return null
        val label = name.trim().ifEmpty { return null }
        return when (source) {
            CarParameterSource.CAN ->
                CarParameter(key, label, source, equation.trim(), unit.trim(), canId = canIdValue!!)
            CarParameterSource.OBD -> {
                val (mode, pid, length) = requestValue!!
                CarParameter(
                    key, label, source, equation.trim(), unit.trim(), header = headerValue, mode = mode, pid = pid,
                    pidLength = length, fast = fast,
                )
            }
        }
    }

    /** The formula's value with its unit on [frame], the scan's last frame of the CAN id; null when none. */
    @Suppress("ReturnCount") // Each early return is a value not there.
    fun valueOn(frame: CanIdRecord?): String? {
        val data = frame?.last?.chunked(2)?.mapNotNull { it.toIntOrNull(Hex)?.toByte() }?.toByteArray() ?: return null
        val v = Equation.parse(equation)?.evaluate(data)?.takeIf { it.isFinite() } ?: return null
        val number = if (kotlin.math.abs(v) >= WholeFrom) fixed(v, 0) else fixed(v, 1)
        return if (unit.isBlank()) number else "$number ${unit.trim()}"
    }

    companion object {
        /** An empty draft for a new CAN parameter. */
        fun empty(): ParameterDraft = ParameterDraft(CarParameterSource.CAN, "", "", "", "", "", false, "")

        /** The steps' fields for [p], to change it. */
        fun of(p: CarParameter): ParameterDraft = ParameterDraft(
            source = p.source,
            canId = if (p.source == CarParameterSource.CAN) hex3(p.canId) else "",
            header = p.header?.toString(Hex)?.uppercase().orEmpty(),
            request = if (p.source == CarParameterSource.OBD) {
                hex2(p.mode) + " " + p.pid.toString(Hex).uppercase().padStart(2 * p.pidLength, '0')
            } else {
                ""
            },
            equation = p.equation,
            unit = p.unit,
            fast = p.fast,
            name = p.name,
        )

        /** A new CAN parameter on [canId], from the CAN bus page. */
        fun can(canId: Int): ParameterDraft = empty().copy(canId = hex3(canId))

        /** A new OBD parameter for mode 01 PID [pid], from the OBD-II page. */
        fun pid(pid: Int): ParameterDraft = empty().copy(source = CarParameterSource.OBD, request = "01 " + hex2(pid))
    }
}

private fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("0x").removePrefix("0X")
    if (t.isEmpty() || t.length > MaxHexDigits || t.any { it.uppercaseChar() !in HexDigits }) return null
    return t.toLong(Hex).toInt()
}

private fun hex3(v: Int) = v.toString(Hex).uppercase().padStart(CanIdDigits, '0')

private fun hex2(v: Int) = v.toString(Hex).uppercase().padStart(2, '0')

private const val Hex = 16
private const val CanIdDigits = 3
private const val HexDigits = "0123456789ABCDEF"
private const val MaxHexDigits = 8
private const val Max11Bit = 0x7FF
private const val Max29Bit = 0x1FFFFFFF
private const val WholeFrom = 100.0

/** Modes 01 to 3E; 3F is not a request. */
private const val MaxMode = 0x3E
