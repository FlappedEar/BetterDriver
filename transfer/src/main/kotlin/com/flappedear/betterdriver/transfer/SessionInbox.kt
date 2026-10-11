package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.recorder.store.SessionFiles
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Brings session bundles into [store] (FBBD-23). A bundle is first unpacked into [staging] and
 * checked; only a complete, undamaged session is then moved into the session folder with one
 * rename, so a failed or cancelled import never leaves half a session in the list. [staging] must
 * be on the same file system as the session folder (both live in the app's files).
 */
class SessionInbox(private val store: SessionStore, private val staging: File) {

    /** Unpacks and checks a bundle; see [SessionBundle.receive]. */
    fun receive(input: InputStream, onBytes: (Int) -> Unit = {}): SessionBundle.Received {
        staging.mkdirs()
        return SessionBundle.receive(input, staging, onBytes)
    }

    /** A session with the same id is already here, so [accept] needs the user's keep-or-replace choice. */
    fun exists(received: SessionBundle.Received): Boolean = store.dir(received.sessionId).exists()

    /**
     * Moves the received session into the list. With an existing session of the same id, [replace]
     * false keeps the one here and drops the received one; true swaps them. A session that is
     * recording is never replaced. Returns whether the received session was added.
     */
    fun accept(received: SessionBundle.Received, replace: Boolean): Boolean {
        val target = store.dir(received.sessionId)
        val here = target.exists()
        try {
            when {
                !here -> move(received.sessionDir, target)
                replace -> swap(received.sessionDir, target)
            }
        } finally {
            discard(received)
        }
        return !here || replace
    }

    private fun swap(incoming: File, target: File) {
        if (store.state(target) == SessionState.RECORDING) throw IOException("that session is recording")
        // Named after the session so [clearStaging] can put it back if the app dies before the swap ends.
        val old = File(staging, OldPrefix + target.name)
        move(target, old)
        try {
            move(incoming, target)
        } catch (e: IOException) {
            move(old, target) // put the session that was here back
            throw e
        }
        old.deleteRecursively()
    }

    /** Drops a received session that will not be added. */
    fun discard(received: SessionBundle.Received) {
        received.staged.deleteRecursively()
    }

    /**
     * Cleans up after an app killed mid-import. Not while importing. A session moved aside by a
     * replace goes back if its replacement never arrived; everything else in [staging] is dropped.
     */
    fun clearStaging() {
        staging.listFiles()?.forEach { f ->
            val id = f.name.removePrefix(OldPrefix)
            val target = store.dir(id)
            if (f.name.startsWith(OldPrefix) && validId(id) && !target.exists()) {
                move(f, target)
            } else {
                f.deleteRecursively()
            }
        }
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("could not move ${from.path} to ${to.path}")
        SessionFiles.syncDir(to.parentFile)
    }

    companion object {
        private const val MaxIdChars = 64
        private const val OldPrefix = "old-"

        /** Session ids are made by [SessionStore.createSession]; anything else could escape the folder. */
        fun validId(id: String) = id.isNotEmpty() && id.length <= MaxIdChars &&
            id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' } &&
            !id.startsWith("-")
    }
}
