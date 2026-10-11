package com.flappedear.betterdriver.gopro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * A BLE connection to one camera. The Android implementation wraps BluetoothGatt; tests use a
 * fake. Calls throw [IOException] on failure.
 */
interface GoProLink {
    /** Connects, discovers services and subscribes to the three response characteristics. */
    suspend fun connect()

    /** Writes one packet (at most 20 bytes) to [characteristic]. */
    suspend fun write(characteristic: String, packet: ByteArray)

    suspend fun read(characteristic: String): ByteArray

    /** Notifications as (characteristic UUID, packet). */
    val notifications: Flow<Pair<String, ByteArray>>

    suspend fun disconnect()
}

/** Thrown when the camera answers with a non-zero status or not at all. */
class GoProException(message: String) : IOException(message)

/**
 * Request/response over a [GoProLink]: one request at a time, packets reassembled per response
 * characteristic, and a timeout on every answer. [scope] collects notifications for as long as
 * the client is used.
 */
class GoProClient(
    private val link: GoProLink,
    scope: CoroutineScope,
    private val timeoutMillis: Long = 5_000,
) {
    private val responses = MutableSharedFlow<Pair<String, OpenGoPro.Response>>(extraBufferCapacity = 16)
    private val assemblers = mutableMapOf<String, OpenGoPro.Assembler>()
    private val mutex = Mutex()

    // Undispatched, so it is subscribed before the first request goes out and no answer is missed.
    private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        link.notifications.collect { (uuid, packet) ->
            val message = assemblers.getOrPut(uuid) { OpenGoPro.Assembler() }.accept(packet) ?: return@collect
            OpenGoPro.Response.parse(message)?.let { responses.emit(uuid to it) }
        }
    }

    /** Sends [request] and returns its response; throws [GoProException] if it fails. */
    suspend fun send(request: OpenGoPro.Request): OpenGoPro.Response = mutex.withLock {
        val responseUuid = GoProUuids.responseFor(request.characteristic)
        try {
            withTimeout(timeoutMillis) {
                val answer = async(start = CoroutineStart.UNDISPATCHED) {
                    responses.first { (uuid, r) -> uuid == responseUuid && r.id == request.id }.second
                }
                for (packet in OpenGoPro.packetize(request.message)) link.write(request.characteristic, packet)
                answer.await()
            }
        } catch (_: TimeoutCancellationException) {
            throw GoProException("no answer to 0x%02x".format(request.id))
        }.also { if (!it.ok) throw GoProException("0x%02x failed with status ${it.status}".format(request.id)) }
    }

    suspend fun read(characteristic: String): ByteArray = mutex.withLock { link.read(characteristic) }

    /** Stops listening to the link. */
    fun close() = collector.cancel()
}
