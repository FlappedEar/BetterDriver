package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.Gate
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.SessionInfo

private const val ChunkIndexDigits = 6

/** File names in a session directory, the same on Android and iOS (see SessionFiles for the layout). */
object SessionFileNames {
    const val META = "meta.properties"
    const val LOCK = "session.lock"
    const val FINISHED = "finished.properties"
    const val RECOVERY = "recovery.properties"
    const val CHUNK_PREFIX = "chunk-"
    const val CHUNK_SUFFIX = ".bdr"

    fun chunkName(index: Int) = CHUNK_PREFIX + index.toString().padStart(ChunkIndexDigits, '0') + CHUNK_SUFFIX

    fun isChunk(name: String) = name.startsWith(CHUNK_PREFIX) && name.endsWith(CHUNK_SUFFIX)
}

/** The entries of meta.properties for session [id] (FBBD-70). */
fun metaEntries(id: String, info: SessionInfo): Map<String, String> = buildMap {
    put("format", "1")
    put("id", id)
    put("startUtcMillis", info.startUtcMillis.toString())
    put("car", info.car)
    put("track", info.track)
    put("devices", info.devices.joinToString("\n"))
    info.startFinish?.let { g ->
        put("startFinish.name", g.name)
        put("startFinish.lat1", g.lat1.toString())
        put("startFinish.lon1", g.lon1.toString())
        put("startFinish.lat2", g.lat2.toString())
        put("startFinish.lon2", g.lon2.toString())
    }
    info.imported?.let { o ->
        put("imported.format", o.format)
        put("imported.file", o.fileName)
        if (o.receiver.isNotEmpty()) put("imported.receiver", o.receiver)
        o.sampleRateHz?.let { put("imported.sampleRateHz", it.toString()) }
    }
    put("channels", info.channels.size.toString())
    info.channels.forEachIndexed { i, c ->
        put("channel.$i.id", c.id.toString())
        put("channel.$i.name", c.name)
        put("channel.$i.unit", c.unit)
        put("channel.$i.source", c.source)
        put("channel.$i.decimals", c.decimals.toString())
        // Added by FBBD-174; older builds ignore it.
        put("channel.$i.provenance", c.provenance.name)
    }
}

/**
 * The session info in meta.properties [p]. Throws a RuntimeException on missing or malformed
 * required entries; callers treat that as damaged metadata.
 */
fun metaFromEntries(p: Map<String, String>): SessionInfo {
    fun req(key: String): String = p[key] ?: throw IllegalArgumentException("meta.properties has no $key")
    val gate = p["startFinish.name"]?.let {
        Gate(
            it,
            req("startFinish.lat1").toDouble(),
            req("startFinish.lon1").toDouble(),
            req("startFinish.lat2").toDouble(),
            req("startFinish.lon2").toDouble(),
        )
    }
    val devices = (p["devices"] ?: "").split("\n").filter { it.isNotEmpty() }
    val imported = p["imported.format"]?.let {
        ImportOrigin(
            it,
            p["imported.file"] ?: "",
            p["imported.receiver"] ?: "",
            p["imported.sampleRateHz"]?.toDoubleOrNull(),
        )
    }
    val n = (p["channels"] ?: "0").toInt()
    val channels = (0 until n).map { i ->
        val def = ChannelDef(
            req("channel.$i.id").toInt(),
            req("channel.$i.name"),
            req("channel.$i.unit"),
            req("channel.$i.source"),
            req("channel.$i.decimals").toInt(),
        )
        // A session stored before FBBD-174 has none: derived from what its metadata says. A kind
        // this version does not know (from a newer app) is unknown, never guessed as measured.
        val provenance = when (val stored = p["channel.$i.provenance"]) {
            null -> Provenance.forSession(def, devices, imported)
            else -> Provenance.parse(stored) ?: Provenance.UNKNOWN
        }
        def.copy(provenance = provenance)
    }
    return SessionInfo(
        startUtcMillis = req("startUtcMillis").toLong(),
        car = p["car"] ?: "",
        track = p["track"] ?: "",
        devices = devices,
        startFinish = gate,
        channels = channels,
        imported = imported,
    )
}
