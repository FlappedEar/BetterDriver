@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.gopro.GoProRecording
import com.flappedear.betterdriver.gopro.GoProRecordingsJson
import com.flappedear.betterdriver.gopro.GoProUuids
import com.flappedear.betterdriver.gopro.OpenGoPro
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.video.VideoFiles
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.posix.memcpy

/**
 * A made-up Open GoPro camera for the UI tests of the download after the drive (FBBD-343), where
 * the simulator has neither Bluetooth nor the camera's Wi-Fi: [prepare] puts a GoPro file riding
 * along a minute of a session ([DemoGoPro]) on the "camera" and the camera's recording note in the
 * session, as a real camera turned on for it would have left them; [link] and [wifi] then answer as
 * the camera would, the files coming from [cardFolder].
 */
class DemoOpenGoPro(private val cardFolder: String) {
    val link: IosGoProLink = Ble()
    val wifi: IosGoProWifi = Wifi()

    private class Ble : IosGoProLink() {
        private val assembler = OpenGoPro.Assembler()

        override fun beginConnect() = connected(null)

        override fun beginWrite(characteristic: String, packet: NSData) {
            written(null)
            val message = assembler.accept(packet.byteArray()) ?: return
            val response = GoProUuids.responseFor(characteristic).uppercase()
            for (p in OpenGoPro.packetize(byteArrayOf(message[0], 0))) notified(response, p.nsData())
        }

        override fun beginRead(characteristic: String) = readDone("GP-DEMO".encodeToByteArray().nsData(), null)

        override fun beginDisconnect() = Unit
    }

    private inner class Wifi : IosGoProWifi() {
        override fun beginJoin(ssid: String, password: String) = joined(null)

        override fun beginLeave(ssid: String) = Unit

        override fun beginGet(url: String) = when {
            url.endsWith("/gopro/camera/keep_alive") -> got("{}", null)
            url.endsWith("/gopro/media/list") -> got(readText("$cardFolder/$ListName"), null)
            else -> got(null, "HTTP 404")
        }

        override fun beginDownload(url: String, toPath: String) {
            val from = "$cardFolder/${url.substringAfterLast('/')}"
            files.removeItemAtPath(toPath, null)
            val ok = files.copyItemAtPath(from, toPath, null)
            val size = size(from)
            downloadProgress(size, size)
            downloaded(if (ok) null else "HTTP 404")
        }
    }

    companion object {
        private const val ListName = "media-list.json"
        private const val FileName = "GX010042.MP4"
        private const val FromMicros = 30_000_000L
        private const val Seconds = 60
        private const val MicrosPerMilli = 1000L
        private const val MillisPerSecond = 1000L

        private val files get() = NSFileManager.defaultManager

        /**
         * Puts the camera's file of session [sessionId] in [cardFolder] and the note of camera
         * [sourceId] in the session. Returns null, or why it could not.
         */
        fun prepare(sessionsRoot: String, sessionId: String, sourceId: String, cardFolder: String): String? {
            files.createDirectoryAtPath(cardFolder, true, null, null)
            val path = "$cardFolder/$FileName"
            DemoGoPro.write(sessionsRoot, sessionId, path, FromMicros, Seconds)?.let { return it }
            val startUtc = SessionStore(sessionsRoot).read(sessionId).info.startUtcMillis
            val created = (startUtc + FromMicros / MicrosPerMilli) / MillisPerSecond
            val list = """{"id":"1","media":[{"d":"100GOPRO","fs":[""" +
                """{"n":"$FileName","cre":"$created","mod":"0","s":"${size(path)}"}]}]}"""
            writeBytesAtomically("$cardFolder/$ListName", list.encodeToByteArray())
            val ackUs = FromMicros - MillisPerSecond * MicrosPerMilli
            val note = GoProRecording(
                sourceId, startAckSessionUs = ackUs, startAckUtcMillis = startUtc + ackUs / MicrosPerMilli,
                stopAckSessionUs = FromMicros + Seconds * MillisPerSecond * MicrosPerMilli,
                stopAckUtcMillis = startUtc + FromMicros / MicrosPerMilli + Seconds * MillisPerSecond,
                cameraUtcOffsetMinutes = 0,
            )
            val videoDir = "$sessionsRoot/$sessionId/${VideoFiles.DIR}"
            files.createDirectoryAtPath(videoDir, true, null, null)
            val notes = "$videoDir/${GoProRecordingsJson.FileName}"
            val all = GoProRecordingsJson.put(readText(notes)?.let(GoProRecordingsJson::decode).orEmpty(), note)
            writeBytesAtomically(notes, GoProRecordingsJson.encode(all).encodeToByteArray())
            return null
        }

        private fun size(path: String): Long =
            (files.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0
    }
}

private fun ByteArray.nsData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.byteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
