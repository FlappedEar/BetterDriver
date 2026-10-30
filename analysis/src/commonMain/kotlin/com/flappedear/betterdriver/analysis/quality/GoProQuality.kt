package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent

// The GoPros in the session validation report (FBBD-183), against FBBD-82 and FBBD-22: how soon each
// controlled camera started and stopped with the session, and how imported files were lined up.

private const val MaxCommandMicros = 3_000_000L

private const val StartCriterion = "GoPro starts within 3 s of the session (FBBD-82, FBBD-22)"
private const val StopCriterion = "GoPro stops within 3 s of the session (FBBD-82, FBBD-22)"
private const val ImportCriterion =
    "imported clips line up by GPS; files from outside the session are rejected (FBBD-22)"

/** Alignment methods that use the camera's GPS. */
private val ByGps = setOf("GPS_MATCH", "GPS_UTC")

/**
 * The GoPro section: one start and stop latency per controlled camera, from its acknowledgements
 * ([EventKinds.GOPRO]) against the session's start (session time 0) and end ([endMicros]); and the
 * alignment of each camera's imported files ([clips] of cameras other than the phone, and
 * [EventKinds.VIDEO_IMPORT] events for files refused). Null when the session had no GoPro.
 */
internal fun goProSection(events: List<RecorderEvent>, clips: List<QualityClip>, endMicros: Long): QualitySection? {
    val acks = events.ofKind(EventKinds.GOPRO).map { it to fields(it.text) }
    val imports = events.ofKind(EventKinds.VIDEO_IMPORT).map { fields(it.text) }
    val imported = clips.filter { it.camera != "phone" }
    if (acks.isEmpty() && imports.isEmpty() && imported.isEmpty()) return null
    val metrics = mutableListOf<QualityMetric>()
    for (camera in acks.mapNotNull { it.second["camera"] }.distinct()) {
        val own = acks.filter { it.second["camera"] == camera }
        metrics += command(camera, "start", own.firstOrNull { it.second["action"] == "start" }, StartCriterion) { it }
        val stop = own.lastOrNull { it.second["action"] == "stop" }
        metrics += command(camera, "stop", stop, StopCriterion) { it - endMicros }
    }
    metrics += importMetric(imported, imports)
    val notes = acks.map { (e, f) -> QualityNote(e.tMicros, "${f["camera"]} ${f["action"]} ${f["result"]}") } +
        imports.filter { it["result"] == "rejected" }.map { QualityNote(0, "${it["camera"]} ${it["file"]} rejected") }
    return QualitySection("GoPro", metrics, notes.sortedBy { it.tMicros })
}

private fun command(
    camera: String,
    action: String,
    ack: Pair<RecorderEvent, Map<String, String>>?,
    criterion: String,
    latency: (Long) -> Long,
): QualityMetric {
    val id = "gopro.$camera.$action"
    val label = "GoPro $action ($camera)"
    if (ack == null) return notMeasured(id, label, criterion, "no $action acknowledgement logged")
    val ok = ack.second["result"] == "ok"
    val delay = latency(ack.first.tMicros)
    return judged(
        id, label, seconds(delay), "s", ok && delay <= MaxCommandMicros, Basis.MEASURED, criterion,
        if (ok) {
            "camera's acknowledgement of the shutter command against the session's $action"
        } else {
            "the camera failed"
        },
    )
}

private fun importMetric(imported: List<QualityClip>, imports: List<Map<String, String>>): QualityMetric {
    val label = "Imported clips lined up by GPS"
    val methods = imported.map { it.method } + imports.filter { it["result"] == "aligned" && imported.isEmpty() }
        .mapNotNull { it["method"] }
    if (methods.isEmpty()) {
        return notMeasured("video.import", label, ImportCriterion, "no GoPro file added to the session")
    }
    val percent = methods.count { it in ByGps } * Percent / methods.size
    val rejected = imports.count { it["result"] == "rejected" }
    return judged(
        "video.import", label, percent, "%", percent >= Percent, Basis.MEASURED, ImportCriterion,
        "${methods.size} clip(s) placed, by ${methods.groupingBy { it.lowercase() }.eachCount()}; " +
            "$rejected file(s) rejected as recorded outside the session",
    )
}
