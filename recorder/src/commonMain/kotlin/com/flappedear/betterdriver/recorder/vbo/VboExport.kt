package com.flappedear.betterdriver.recorder.vbo

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState

private const val MinutesPerDegree = 60.0
private const val MicrosPerMilli = 1_000.0

/** OBD-II channels (core-ble ObdSource), interpolated between readings in a VBO (FBBD-303). */
private const val ObdSource = "obd"

/** Two OBD-II readings further apart than this are not joined by a line: the value is held. */
private const val MaxInterpolationGapMicros = 1_000_000L

/**
 * Converts a stored session (finished, recovered or interrupted) to VBO (FBBD-66, FBBD-71).
 *
 * One data row per GNSS fix (a sample set holding latitude and longitude). Other channels are
 * sample-and-hold: each row carries the latest value seen at or before the fix. OBD-II channels are
 * interpolated instead, as RaceChrono's VBO export does (FBBD-303): a fix between two readings at
 * most [MaxInterpolationGapMicros] apart gets the value on the straight line between them, so RPM
 * reads as a line rather than steps. Before a channel's first value the first value is used; a
 * channel with no values at all is left out.
 * If a fix has no GNSS UTC time, session start time plus elapsed time is used.
 * With [VboVideo], each row also gets avifileindex and avisynctime, and the VBO gets an [avi] section (FBBD-25).
 * The data then starts at the first video's start: Circuit Tools plays video file 1 from the first
 * data row and ignores its avisynctime offset (FBBD-51, checked by Arek with sync kit variant E).
 * [derived] channels, worked out from the recording rather than recorded, get columns of their own
 * after the recorded ones (the car's G for FlappedEar Overlays, see [VboDerivedChannel]).
 * No text in the file names RaceBox: Circuit Tools treats a file that does as a RaceBox app export,
 * checks for a RaceBox checksum and closes it with "Checksum failed. It's not genuine RACEBOX VBO file" (FBBD-184).
 */
object VboExport {

    /** Standard columns, in this order, from the matching [GnssChannels]. */
    private val STANDARD = listOf(
        GnssChannels.SATS to VboColumn("satellites", "sats", "", VboWriter::sats),
        GnssChannels.UTC_MS to VboColumn("time", "time", "", VboWriter::time),
        GnssChannels.LAT to VboColumn("latitude", "lat", "", VboWriter::latitude),
        GnssChannels.LON to VboColumn("longitude", "long", "", VboWriter::longitude),
        GnssChannels.SPEED_KMH to VboColumn("velocity kmh", "velocity", "", VboWriter::velocity),
        GnssChannels.HEADING to VboColumn("heading", "heading", "", VboWriter::heading),
        GnssChannels.ALT_M to VboColumn("height", "height", "", VboWriter::height),
    )

    /** What an export wrote (FBBD-14): [rows] data rows for the session's [fixes] GNSS fixes it covers. */
    data class Written(val rows: Int, val fixes: Int)

    /** Writes [session] as VBO text to [out]; the JVM's VboExporter writes it to a stream as UTF-8. */
    fun export(
        session: SessionData,
        out: Appendable,
        video: VboVideo? = null,
        derived: List<VboDerivedChannel> = emptyList(),
    ): Written {
        // Sources queue independently, so file order is not time order; sample-and-hold needs time order.
        // The sort is stable: sets with equal times keep their file order.
        val sets = session.sampleSets.sortedBy { it.tMicros }
        val from = video?.startMicros ?: Long.MIN_VALUE
        val fixes = sets.count { isFix(it) && it.tMicros >= from }
        var rows = 0
        val firstValue = HashMap<Int, Double>()
        for (set in sets) for (v in set.values) if (v.channelId !in firstValue) firstValue[v.channelId] = v.value
        val extra = session.channels.values
            .filter { it.id !in GnssChannels.IDS && it.id in firstValue }
            .sortedBy { it.id }

        val derivedHere = derived.filter { it.tMicros.isNotEmpty() }
        VboWriter(out).write(
            VboDocument(
                createdUtcMillis = session.info.startUtcMillis,
                columns = STANDARD.map { it.second } + extraColumns(extra, derivedHere) +
                    derivedHere.map { VboColumn(it.name, it.name, it.unit, VboWriter.decimal(it.decimals)) } +
                    listOfNotNull(video?.indexColumn, video?.syncColumn),
                comments = comments(session, video),
                gates = gates(session),
                aviBaseName = video?.baseName,
                aviExtension = video?.takeIf { it.extensionLine }?.extension,
                rows = rows(session, sets, extra, firstValue, derivedHere, video).onEach { rows++ },
            ),
        )
        return Written(rows, fixes)
    }

    private fun extraColumns(extra: List<ChannelDef>, derived: List<VboDerivedChannel>): List<VboColumn> {
        // Unique against standard header and column names, so a custom channel can never pose as a standard one,
        // and against the derived columns, which keep their names because readers look for them.
        val used = (STANDARD.flatMap { listOf(it.second.columnName, it.second.headerName) } + derived.map { it.name })
            .toMutableSet()
        return extra.map { c ->
            val base = neutral(c.name).replace(Regex("\\s+"), "_").ifEmpty { "ch${c.id}" }
            var name = base
            var attempt = 0
            // A suffixed name can itself be taken (channel 299 named "x_300", channel 300 named "x"): retry.
            while (name in VboWriter.STANDARD_CHANNELS || !used.add(name)) {
                attempt++
                name = if (attempt == 1) "${base}_${c.id}" else "${base}_${c.id}_$attempt"
            }
            VboColumn(name, name, neutral(c.unit), VboWriter.decimal(c.decimals))
        }
    }

    private fun comments(session: SessionData, video: VboVideo?) = commentLines(session, video).map(::neutral)

    private fun commentLines(session: SessionData, video: VboVideo?) = buildList {
        add("Generated by BetterDriver")
        add("Session ${session.id}")
        if (session.info.car.isNotBlank()) add("Car ${session.info.car}")
        if (session.info.track.isNotBlank()) add("Track ${session.info.track}")
        session.info.devices.forEach { add("Device $it") }
        session.info.imported?.let { add("Imported from ${it.format} ${it.fileName}".trim()) }
        if (session.state == SessionState.RECOVERED || session.state == SessionState.INTERRUPTED) {
            add("Recovered after an interrupted recording")
        }
        video?.orderedClips?.firstOrNull()?.let { add("Video ${it.camera}") }
    }

    private fun gates(session: SessionData) = listOfNotNull(
        session.info.startFinish?.let {
            VboGate(
                "Start",
                -it.lon1 * MinutesPerDegree,
                it.lat1 * MinutesPerDegree,
                -it.lon2 * MinutesPerDegree,
                it.lat2 * MinutesPerDegree,
                neutral(it.name),
            )
        },
    )

    @Suppress("LongParameterList") // Each is one part of a row.
    private fun rows(
        session: SessionData,
        sets: List<SampleSet>,
        extra: List<ChannelDef>,
        firstValue: Map<Int, Double>,
        derived: List<VboDerivedChannel>,
        video: VboVideo?,
    ) = sequence {
        val extraIds = extra.map { it.id }
        val width = STANDARD.size + extraIds.size + derived.size + if (video != null) 2 else 0
        val latest = HashMap(firstValue)
        val lines = extra.filter { it.source == ObdSource }.associate { it.id to Readings.of(sets, it.id) }
        // Sample-and-hold like the recorded channels: the newest derived value at or before each fix.
        val next = IntArray(derived.size)
        for (set in sets) {
            set.values.forEach { latest[it.channelId] = it.value }
            val beforeVideo = video?.startMicros?.let { set.tMicros < it } ?: false
            if (isFix(set) && !beforeVideo) {
                val row = DoubleArray(width)
                STANDARD.forEachIndexed { i, (ch, _) -> row[i] = latest[ch.id] ?: 0.0 }
                if (set.values.none { it.channelId == GnssChannels.UTC_MS.id }) {
                    row[TIME_INDEX] = session.info.startUtcMillis + set.tMicros / MicrosPerMilli
                }
                extraIds.forEachIndexed { i, id -> row[STANDARD.size + i] = valueAt(id, set.tMicros, lines, latest) }
                derived.forEachIndexed { i, d ->
                    while (next[i] < d.tMicros.size && d.tMicros[next[i]] <= set.tMicros) next[i]++
                    row[STANDARD.size + extraIds.size + i] = d.values[(next[i] - 1).coerceAtLeast(0)]
                }
                if (video != null) {
                    val (index, sync) = video.columnsAt(set.tMicros)
                    row[width - 2] = index
                    row[width - 1] = sync
                }
                yield(row)
            }
        }
    }

    /** [id]'s value at [tMicros]: on its line when it is interpolated ([lines]), else the latest held. */
    private fun valueAt(id: Int, tMicros: Long, lines: Map<Int, Readings>, latest: Map<Int, Double>): Double =
        lines[id]?.at(tMicros) ?: latest[id] ?: 0.0

    /** Replaces "RaceBox" in any case with "gnss", or "GNSS" when not all lower case (see the class comment). */
    internal fun neutral(text: String) =
        text.replace(RACEBOX) { if (it.value == it.value.lowercase()) "gnss" else "GNSS" }

    private val RACEBOX = Regex("race\\s*box", RegexOption.IGNORE_CASE)

    private fun isFix(set: SampleSet) =
        set.values.any { it.channelId == GnssChannels.LAT.id } && set.values.any { it.channelId == GnssChannels.LON.id }

    private val TIME_INDEX = STANDARD.indexOfFirst { it.first == GnssChannels.UTC_MS }
}

/**
 * One channel's readings in time order, read at ascending times by [at]: the value on the line
 * between the readings either side when they are at most [MaxInterpolationGapMicros] apart, else
 * the latest at or before, else the first. Null when the channel has no reading.
 */
private class Readings(private val t: LongArray, private val v: DoubleArray) {
    private var next = 0

    fun at(tMicros: Long): Double? {
        while (next < t.size && t[next] <= tMicros) next++
        val i = next - 1
        return when {
            t.isEmpty() -> null
            next == 0 -> v[0]
            next == t.size || t[i] == tMicros || t[next] - t[i] > MaxInterpolationGapMicros -> v[i]
            else -> v[i] + (v[next] - v[i]) * (tMicros - t[i]).toDouble() / (t[next] - t[i])
        }
    }

    companion object {
        /** The finite readings of [channelId] in [sets], which are in time order. */
        fun of(sets: List<SampleSet>, channelId: Int): Readings {
            val t = ArrayList<Long>()
            val v = ArrayList<Double>()
            for (set in sets) for (value in set.values) {
                if (value.channelId == channelId && value.value.isFinite()) {
                    t += set.tMicros
                    v += value.value
                }
            }
            return Readings(t.toLongArray(), v.toDoubleArray())
        }
    }
}
