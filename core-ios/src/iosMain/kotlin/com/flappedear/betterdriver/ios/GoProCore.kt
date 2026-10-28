package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.setup.Setup
import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.ClipStatus
import com.flappedear.betterdriver.video.VideoSourceKind
import kotlin.math.roundToLong

/**
 * A GoPro or other action camera in the equipment list, as the sessions list needs it (Android's
 * ListedGoPro): its name, and whether the app downloads its files ([download], Open GoPro) or the
 * driver adds them. [legacySourceId] is the camera id its files have in sessions recorded before FBBD-105.
 */
data class ListedCamera(val sourceId: String, val name: String, val download: Boolean, val legacySourceId: String?) {
    /** True when [id], a camera of some session, is this one. */
    fun answersFor(id: String): Boolean = id == sourceId || id == legacySourceId
}

/** One camera's lined-up clips in a session, as Android's session card shows them (UI-31, UI-32). */
data class SessionCameraLine(
    val sourceId: String,
    val name: String,
    /** "GoPro HERO8 · 2 clips lined up". */
    val text: String,
    /** "by GPS ±0.05 s". */
    val how: String,
    /** Green dot when placed by GPS, amber otherwise. */
    val byGps: Boolean,
)

/** Action cameras for the iPhone's sessions list (FBBD-343), on Android's rules. */
object GoProCore {
    /**
     * The GoPros and other action cameras in the equipment list: Open GoPro ones download, the others
     * add files. Sessions from before FBBD-105 name their camera [CameraIds.GOPRO_HERO11] or
     * [CameraIds.GOPRO_HERO8]; the first matching camera answers for them, as on Android.
     */
    fun listed(setup: Setup): List<ListedCamera> {
        val hero8 = setup.equipment.firstOrNull {
            it.model == EquipmentModel.GOPRO_FILES && it.goPro == GoProModel.HERO8_BLACK
        }
        val listed = setup.equipment.filter { it.model.actionCamera }.map {
            val legacy = if (it.id == hero8?.id) CameraIds.GOPRO_HERO8 else null
            ListedCamera(CameraIds.goPro(it.id), it.name, download = it.model == EquipmentModel.GOPRO, legacy)
        }
        val hero11 = setup.equipment.firstOrNull {
            it.model == EquipmentModel.GOPRO && it.address != null && it.goPro == GoProModel.HERO11_BLACK
        }
        return if (hero11 == null) listed else listed + ListedCamera(CameraIds.GOPRO_HERO11, hero11.name, true, null)
    }

    /** The listed cameras started by hand that have no video in session [sessionId] yet: each offers Add. */
    fun addable(setup: Setup, sessionsRoot: String, sessionId: String): List<ListedCamera> {
        val cameras = clipsBySource(sessionsRoot, sessionId).keys
        return listed(setup).filter { l -> !l.download && cameras.none { l.answersFor(it) } }
    }

    /** The lined-up clips of each camera in session [sessionId], one line per camera. */
    fun lines(setup: Setup, sessionsRoot: String, sessionId: String): List<SessionCameraLine> {
        val listed = listed(setup)
        return clipsBySource(sessionsRoot, sessionId).map { (id, clips) ->
            val name = listed.firstOrNull { it.answersFor(id) }?.name ?: legacyName(id)
            val worst = clips.maxBy { it.alignment.uncertaintyUs }.alignment
            val byGps = worst.method == AlignmentMethod.GPS_MATCH || worst.method == AlignmentMethod.GPS_UTC
            val count = if (clips.size == 1) "1 clip" else "${clips.size} clips"
            SessionCameraLine(id, name, "$name · $count lined up", alignmentText(worst), byGps)
        }
    }

    /** The kind of files camera [sourceId] adds: another action camera's, or a GoPro's (FBBD-120). */
    fun kindFor(setup: Setup, sourceId: String): VideoSourceKind {
        val other = setup.equipment.any {
            it.model == EquipmentModel.CAMERA_FILES && CameraIds.goPro(it.id) == sourceId
        }
        return if (other) VideoSourceKind.CAMERA_FILES else VideoSourceKind.GOPRO_FILES
    }

    /** The cameras Settings offers for a GoPro started by hand, newest generations first (FBBD-105). */
    val fileImportModels: List<GoProModel> = GoProModel.FileImport + GoProModel.OpenGoPro

    /** A camera's name in the "Which GoPro" list, as on Android. */
    fun choiceLabel(model: GoProModel): String = when (model) {
        GoProModel.OTHER_OPEN_GOPRO -> "Other GoPro with Open GoPro"
        GoProModel.OTHER -> "Other GoPro"
        else -> model.label
    }

    private fun clipsBySource(sessionsRoot: String, sessionId: String) =
        ClipIndex("$sessionsRoot/$sessionId/${VideoFiles.DIR}").clips()
            .filter { it.status == ClipStatus.LOCAL && it.kind.isGoPro }
            .groupBy { it.sourceId }

    private fun legacyName(sourceId: String): String = when (sourceId) {
        CameraIds.GOPRO_HERO11 -> "GoPro HERO11"
        CameraIds.GOPRO_HERO8 -> "GoPro HERO8"
        else -> "GoPro"
    }
}

/** How a clip was placed, as Android's card says it: "by GPS ±0.05 s", "by camera clock ±1800 s". */
internal fun alignmentText(a: Alignment): String {
    val how = when (a.method) {
        AlignmentMethod.GPS_MATCH, AlignmentMethod.GPS_UTC -> "by GPS"
        AlignmentMethod.COMMAND_ACK -> "by start time"
        AlignmentMethod.CAMERA_CLOCK -> "by camera clock"
        AlignmentMethod.CAPTURE_CLOCK -> "by phone clock"
        AlignmentMethod.MANUAL -> "by hand"
    }
    val seconds = a.uncertaintyUs / MicrosPerSecond
    val text = if (seconds < 1) {
        val hundredths = (seconds * Hundred).roundToLong()
        "${hundredths / Hundred}.${(hundredths % Hundred).toString().padStart(2, '0')} s"
    } else {
        "${seconds.roundToLong()} s"
    }
    return "$how ±$text"
}

private const val MicrosPerSecond = 1_000_000.0
private const val Hundred = 100
