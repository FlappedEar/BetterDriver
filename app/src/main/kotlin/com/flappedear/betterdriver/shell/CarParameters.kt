@file:Suppress("TooManyFunctions") // The car page's texts, each small.

package com.flappedear.betterdriver.shell

import androidx.compose.runtime.saveable.Saver
import com.flappedear.betterdriver.obd.CanBroadcasts
import com.flappedear.betterdriver.obd.OwnChannels
import com.flappedear.betterdriver.obd.StandardPids
import com.flappedear.betterdriver.obd.SupportedPids
import com.flappedear.betterdriver.obd.equation.Equation
import com.flappedear.betterdriver.setup.CanIdRecord
import com.flappedear.betterdriver.setup.CarParameter
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile
import com.flappedear.betterdriver.setup.CarScanRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * What the car's page says about the driver's own parameters and the last scan (FBBD-344), kept
 * apart from the screens so it is tested on the JVM.
 */

/** Where [p] comes from, as one line: "CAN 0D4 · bytesToUIntLe(raw, 4, 2)" or "OBD 7E0 01 06 · A-40". */
internal fun parameterSource(p: CarParameter): String = when (p.source) {
    CarParameterSource.CAN -> "CAN ${hex3(p.canId)} · ${p.equation}"
    CarParameterSource.OBD -> {
        val header = p.header?.let { "${it.toString(Hex).uppercase()} " }.orEmpty()
        "OBD $header${hex2(p.mode)} ${p.pid.toString(Hex).uppercase().padStart(2 * p.pidLength, '0')} · ${p.equation}"
    }
}

/** The mode 01 PIDs a scan found, without the bitmask PIDs (01 00, 01 20 …) that only list the others. */
internal fun reportedPids(scan: CarScanRecord): List<Int> = scan.supported.filter { it !in SupportedPids.bitmaskPids }

/** Why a scan heard no CAN ids. */
internal fun noCanReason(scan: CarScanRecord): String {
    val p = scan.protocol.trim().uppercase().removePrefix("A")
    return if (p == "6" || p == "8") "the adapter would not listen" else "the car is not on 11-bit CAN"
}

/** When a scan ran, short: "today 14:02" or "28 Oct 14:02". */
internal fun scanTime(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    val time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(atMs))
    return if (day.format(Date(atMs)) == day.format(Date(nowMs))) {
        "today $time"
    } else {
        SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(atMs)) + " $time"
    }
}

/** The bytes of an id that moved, as "bytes 0–1, 4 change", "byte 2 changes" or "no byte changed". */
internal fun changingText(changing: List<Int>): String {
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
 * The names the app or the driver gives CAN id [id] on [car]: the driver's own parameters on it, and
 * the broadcasts the app reads on an FR-S (FBBD-320); empty for an id nobody reads.
 */
internal fun canIdNames(car: CarProfile, id: Int, frsCar: Boolean): List<String> {
    val own = car.parameters.filter { it.source == CarParameterSource.CAN && it.canId == id }.map { it.name }
    val builtIn = if (frsCar) CanBroadcasts.Frs.filter { it.canId == id }.map { it.name } else emptyList()
    return (builtIn + own).distinct()
}

/** Whether the app reads the FR-S broadcasts on [car]: by its name, or the VIN its scan read. */
internal fun readsFrsBroadcasts(car: CarProfile, nameSaysSo: Boolean): Boolean {
    val vin = car.scan?.vinPrefix?.uppercase() ?: return nameSaysSo
    return nameSaysSo || CanBroadcasts.VinPrefixes.any { vin.startsWith(it) }
}

/** How often a CAN id was heard: "50/s", or "2.5/s" below [WholeRateFrom]. */
internal fun rate(perSecond: Double): String =
    if (perSecond >= WholeRateFrom) "${perSecond.toInt()}/s" else String.format(Locale.ROOT, "%.1f/s", perSecond)

/** From this many frames a second the rate is shown without a decimal. */
private const val WholeRateFrom = 10

/** The standard channel the app has for mode 01 PID [pid], or null when it has no formula for it. */
internal fun standardName(pid: Int): String? = StandardPids.all.firstOrNull { it.pid == pid }?.name

/** The id of the app's channel for mode 01 PID [pid], or null. */
internal fun standardId(pid: Int): String? = StandardPids.all.firstOrNull { it.pid == pid }?.id

/**
 * A parameter being added or changed in the steps (UI-302 B), as typed. [canId], [header] and
 * [request] are hex text: "0D4"; "7E0" or empty for every ECU; "01 06" or "22 F190".
 */
data class ParameterDraft(
    val source: CarParameterSource = CarParameterSource.CAN,
    val canId: String = "",
    val header: String = "",
    val request: String = "",
    val equation: String = "",
    val unit: String = "",
    val fast: Boolean = false,
    val name: String = "",
) {
    /** The CAN id, when it is a valid 11-bit one. */
    val canIdValue: Int? get() = parseHex(canId)?.takeIf { it in 0..Max11Bit }

    /** The header, null for every ECU; [headerValid] is false for text that is not one. */
    val headerValue: Int? get() = parseHex(header)
    val headerValid: Boolean get() = header.isBlank() || headerValue?.let { it in 0..Max29Bit } == true

    /** Mode, PID and its length in bytes, when [request] is two or three hex bytes. */
    val requestValue: Triple<Int, Int, Int>? get() {
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

    /** The value of the formula on [frame], the last frame of the CAN id heard in the scan; null when none. */
    fun valueOn(frame: CanIdRecord?): Double? {
        val data = frame?.last?.chunked(2)?.mapNotNull { it.toIntOrNull(Hex)?.toByte() }?.toByteArray() ?: return null
        return Equation.parse(equation)?.evaluate(data)?.takeIf { it.isFinite() }
    }

    companion object {
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
        fun can(canId: Int) = ParameterDraft(CarParameterSource.CAN, canId = hex3(canId))

        /** A new OBD parameter for mode 01 PID [pid], from the OBD-II page. */
        fun pid(pid: Int) = ParameterDraft(CarParameterSource.OBD, request = "01 " + hex2(pid))
    }
}

/** Keeps a [ParameterDraft] across a configuration change, as one string. */
internal val DraftSaver: Saver<ParameterDraft, String> =
    Saver(save = { encodeDraft(it) }, restore = { decodeDraft(it) })

/** [DraftSaver] for a draft that may be none (an empty string). */
internal val OptionalDraftSaver: Saver<ParameterDraft?, String> =
    Saver(save = { it?.let(::encodeDraft).orEmpty() }, restore = { if (it.isEmpty()) null else decodeDraft(it) })

private const val Sep = '\u0001'

private fun encodeDraft(d: ParameterDraft): String =
    listOf(d.source.name, d.canId, d.header, d.request, d.equation, d.unit, d.fast.toString(), d.name)
        .joinToString(Sep.toString())

@Suppress("MagicNumber", "ReturnCount") // Field positions of encodeDraft; each early return is a damaged draft.
private fun decodeDraft(text: String): ParameterDraft? {
    val f = text.split(Sep)
    if (f.size != DraftFields) return null
    val source = CarParameterSource.entries.firstOrNull { it.name == f[0] } ?: return null
    return ParameterDraft(source, f[1], f[2], f[3], f[4], f[5], f[6].toBoolean(), f[7])
}

private const val DraftFields = 8

/** The channel id the parameter stored as [key] is read and recorded with. */
internal fun ownChannelId(key: String) = OwnChannels.id(key)

private fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("0x").removePrefix("0X")
    if (t.isEmpty() || t.length > MaxHexDigits || t.any { it.uppercaseChar() !in HexDigits }) return null
    return t.toLong(Hex).toInt()
}

internal fun hex3(v: Int) = v.toString(Hex).uppercase().padStart(CanIdDigits, '0')

private fun hex2(v: Int) = v.toString(Hex).uppercase().padStart(2, '0')

private const val Hex = 16
private const val CanIdDigits = 3
private const val HexDigits = "0123456789ABCDEF"
private const val MaxHexDigits = 8
private const val Max11Bit = 0x7FF
private const val Max29Bit = 0x1FFFFFFF

/** Modes 01 to 3E; 3F is not a request. */
private const val MaxMode = 0x3E
