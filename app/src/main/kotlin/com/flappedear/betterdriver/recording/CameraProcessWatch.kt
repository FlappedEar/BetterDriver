package com.flappedear.betterdriver.recording

import android.os.IBinder
import android.os.RemoteException

/**
 * Notices when the camera process dies during a recording (FBBD-470, review F7). The camera service
 * runs in its own process and is not restarted (START_NOT_STICKY), so a dead camera process means no
 * more video for the session; until now the recorder kept the camera's last report ("1080p · 60",
 * green) and the track screen looked healthy. Each camera report names its sender ([reported]); the
 * watch links to that sender's death, and [gone] runs once on the main thread if it dies while still
 * watched. [forget] drops the watch when the recording ends.
 *
 * [T] is the sender's handle: the camera's binder in the app, any key in the tests. Main thread only.
 */
internal class CameraProcessWatch<T : Any>(
    /** Links [gone] to the death of a sender; returns how to unlink. */
    private val link: (sender: T, died: () -> Unit) -> () -> Unit,
    /** Runs on the main thread (a death is reported on a binder thread). */
    private val post: (() -> Unit) -> Unit,
    private val gone: () -> Unit,
) {
    private var watched: T? = null
    private var unlink: (() -> Unit)? = null

    /** A camera report from [sender] (null when the report did not say who sent it). */
    fun reported(sender: T?) {
        if (sender == null || sender === watched) return
        forget()
        watched = sender
        unlink = link(sender) {
            post {
                if (watched === sender) {
                    forget()
                    gone()
                }
            }
        }
    }

    /** Stops watching: the recording ended. */
    fun forget() {
        unlink?.invoke()
        unlink = null
        watched = null
    }
}

/** Links [died] to [binder]'s death; a binder that is already dead calls it at once. */
internal fun linkDeath(binder: IBinder, died: () -> Unit): () -> Unit {
    val recipient = IBinder.DeathRecipient { died() }
    val linked = try {
        binder.linkToDeath(recipient, 0)
        true
    } catch (_: RemoteException) {
        false
    }
    if (!linked) {
        died()
        return {}
    }
    return { runCatching { binder.unlinkToDeath(recipient, 0) } }
}
