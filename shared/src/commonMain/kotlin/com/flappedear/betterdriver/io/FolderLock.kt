package com.flappedear.betterdriver.io

/**
 * Runs [change] holding folder [dir]'s lock: one per folder for the threads of this process, and a
 * file lock for other processes. The lock file sits next to the folder (`<dir>.lock`), so the folder
 * holds only what is kept there and a backup or restore of it never meets a lock file. Reentrant: a
 * change may take the same folder's lock again. [dir] is made first. Blocking.
 */
expect fun <T> withFolderLock(dir: String, change: () -> T): T
