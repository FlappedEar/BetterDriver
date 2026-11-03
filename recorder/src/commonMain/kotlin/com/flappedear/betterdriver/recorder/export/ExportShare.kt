package com.flappedear.betterdriver.recorder.export

/**
 * What a session's Export writes (FBBD-150): VBO for Circuit Tools and other VBO readers, VBO with
 * its video files (FBBD-25), CSV with every channel, GPX for maps. VBO has no registered type; a
 * generic one keeps the .vbo file name.
 */
enum class ExportFormat(val extension: String, val mimeType: String) {
    Vbo("vbo", "application/octet-stream"),

    /** The VBO with its video files next to it (FBBD-25). */
    VboVideo("vbo", "application/octet-stream"),
    Csv("csv", "text/csv"),
    Gpx("gpx", "application/gpx+xml"),
}

/**
 * A written export, ready for the system share sheet (FBBD-443): the same route as Share log on both
 * platforms, so the files go to AirDrop, Quick Share, Mail, Files or Drive in one step instead of a
 * save first and a send after. [files] are full paths, the VBO first; VBO + video lists each video
 * file as its own item, so saving them puts them side by side as Circuit Tools reads them. [mimeType]
 * covers every item; [subject] names the export for mail; [title] heads the sheet.
 */
data class ExportShare(
    val id: String,
    val format: ExportFormat,
    val files: List<String>,
    val mimeType: String,
    val subject: String,
    val title: String,
) {
    /** The log line for this share, as Share log writes one: which export, how many files, how big. */
    fun logLine(bytes: Long): String {
        val count = if (files.size == 1) "1 file" else "${files.size} files"
        return "export $id as ${format.name}: $count, ${bytes / BytesPerKb} KB, opening the share sheet"
    }

    companion object {
        /** The share sheet's title on Android, as "Share log". */
        const val Title = "Share export"

        /** Every file type at once, for VBO + video's mix of VBO and MP4. */
        const val AnyType = "*/*"

        private const val BytesPerKb = 1_000L

        /** "BetterDriver-<id>": the folder, file and video names of every export of session [id]. */
        fun baseName(id: String) = "BetterDriver-$id"

        /** The export's file name: BetterDriver-<id>.vbo, .csv or .gpx. */
        fun fileName(id: String, format: ExportFormat) = "${baseName(id)}.${format.extension}"

        /** The start of the video files' names next to the VBO: BetterDriver-<id>_0001.MP4 and on. */
        fun videoPrefix(id: String) = "${baseName(id)}_"

        /**
         * The share of session [id]'s export as [format]: [written] is the VBO, CSV or GPX file and
         * [videos] the video files written next to it (VBO + video only), in their order.
         */
        fun of(id: String, format: ExportFormat, written: String, videos: List<String> = emptyList()): ExportShare {
            require(videos.isEmpty() || format == ExportFormat.VboVideo) { "only VBO + video carries video files" }
            val files = listOf(written) + videos
            return ExportShare(
                id = id,
                format = format,
                files = files,
                mimeType = if (videos.isEmpty()) format.mimeType else AnyType,
                subject = if (videos.isEmpty()) fileName(id, format) else "${baseName(id)} VBO + video",
                title = Title,
            )
        }
    }
}
