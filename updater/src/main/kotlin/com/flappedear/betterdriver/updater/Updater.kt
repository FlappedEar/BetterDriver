package com.flappedear.betterdriver.updater

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Where the updater is, for the settings row and the session list. */
sealed interface UpdateState {
    /** Nothing checked yet in this run of the app. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val release: Release) : UpdateState

    data class Downloading(val release: Release, val bytes: Long) : UpdateState

    /** Downloaded and checked; Android's installer may be offered. */
    data class Ready(val release: Release, val apk: File) : UpdateState

    data class Failed(val message: String, val release: Release? = null) : UpdateState

    /** A session is recording or not saved yet, so nothing was done (FBBD-74). */
    data object Paused : UpdateState
}

/** When the next automatic check may run, kept across app starts. Times are epoch milliseconds. */
interface CheckLog {
    var nextAutoCheck: Long

    /** GitHub's rate limit for unauthenticated calls was hit; no call goes out before this. */
    var rateLimitedUntil: Long
}

/**
 * The in-app updater (FBBD-73), modelled on FlappedEar Telemetry's. It asks the GitHub Releases
 * API of FlappedEar/BetterDriver for the newest vX.Y.Z release (pre-releases included, no sign-in),
 * downloads its APK into [dir] and keeps it only if its size and SHA-256 match the release's
 * SHA256SUMS.txt and [apkCheck] accepts it (package, version, pinned release certificate). Any
 * other file is deleted. It never installs anything itself.
 *
 * Nothing happens while [busy] is true, which the app sets while a session records or is not yet
 * saved (FBBD-74): no network call, no file, no prompt. A download in progress stops when a
 * session starts.
 *
 * Calls block; the app makes them one at a time off the main thread.
 */
// Guard clauses: each failed step ends early with its own message. Every collaborator is injected for tests.
@Suppress("ReturnCount", "LongParameterList")
class Updater(
    private val network: Network,
    private val current: Version,
    private val dir: File,
    private val inspect: (File) -> ApkInfo?,
    private val apkCheck: ApkCheck,
    private val busy: () -> Boolean,
    private val log: CheckLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val releasesUrl: String = ReleasesUrl,
) {

    /** The launch check: at most once a day. Null when it is not due. */
    fun checkIfDue(): UpdateState? = if (clock() < log.nextAutoCheck) null else check()

    /** "Check for updates", and the daily check. */
    fun check(): UpdateState {
        if (busy()) return UpdateState.Paused
        val now = clock()
        if (now < log.rateLimitedUntil) return RateLimited
        val response = try {
            network.get(releasesUrl, ApiHeaders)
        } catch (_: IOException) {
            log.nextAutoCheck = now + OfflineRetryMillis
            return UpdateState.Failed("No connection to GitHub. Try again later.")
        }
        if (isRateLimited(response)) {
            log.rateLimitedUntil = rateLimitEnd(response, now)
            log.nextAutoCheck = log.rateLimitedUntil
            return RateLimited
        }
        log.nextAutoCheck = now + DayMillis
        if (!response.ok) return UpdateState.Failed("Update check failed (HTTP ${response.code}).")
        val newest = try {
            Releases.newest(response.body.decodeToString())
        } catch (_: IllegalArgumentException) {
            return UpdateState.Failed("Update check failed: GitHub sent something unexpected.")
        }
        return if (newest == null || newest.version <= current) {
            clearDownloads()
            UpdateState.UpToDate
        } else {
            UpdateState.Available(newest)
        }
    }

    /** Downloads and checks [release]; [onProgress] gets the bytes so far. */
    fun download(release: Release, onProgress: (Long) -> Unit = {}): UpdateState {
        if (busy()) return UpdateState.Paused
        clearDownloads()
        // The size comes from unauthenticated release data; a cap keeps a bad release from filling storage.
        if (release.apk.size > MaxApkBytes) return failed(release, "The release APK is too large to be BetterDriver.")
        if (!dir.isDirectory && !dir.mkdirs()) return failed(release, "Could not save the update.")
        val expected = when (val sums = checksums(release)) {
            is Sums.Ok -> sums.byName[release.apk.name]
                ?: return failed(release, "The release has no checksum for its APK, so it was not downloaded.")
            is Sums.Error -> return failed(release, sums.message)
        }
        // A session may have started while the checksums were fetched.
        if (busy()) return UpdateState.Paused
        val part = File(dir, release.apk.name + PartSuffix)
        val response = try {
            FileOutputStream(part).use { out ->
                network.download(release.apk.url, out, release.apk.size) { bytes ->
                    onProgress(bytes)
                    !busy()
                }
            }
        } catch (e: IOException) {
            part.delete()
            if (busy()) return UpdateState.Paused
            return failed(release, "Download failed: ${e.message ?: "no connection"}.")
        }
        return verify(release, part, response, expected)
    }

    /** True when Android's installer may be offered: no session is recording or unsaved. */
    fun mayInstall(): Boolean = !busy()

    private fun verify(release: Release, part: File, response: Response, expected: String): UpdateState {
        val problem = when {
            busy() -> null
            !response.ok -> "Download failed (HTTP ${response.code})."
            part.length() != release.apk.size -> "The download is incomplete."
            sha256(part) != expected -> "The download does not match its checksum."
            else -> null
        }
        if (busy() || problem != null) {
            part.delete()
            return problem?.let { failed(release, it) } ?: UpdateState.Paused
        }
        val apk = File(dir, release.apk.name)
        if (!part.renameTo(apk)) {
            part.delete()
            return failed(release, "Could not save the update.")
        }
        apkCheck.problem(inspect(apk), release.version)?.let {
            apk.delete()
            return failed(release, it)
        }
        return UpdateState.Ready(release, apk)
    }

    private sealed interface Sums {
        class Ok(val byName: Map<String, String>) : Sums
        class Error(val message: String) : Sums
    }

    private fun checksums(release: Release): Sums {
        val response = try {
            network.get(release.checksums.url)
        } catch (_: IOException) {
            return Sums.Error("No connection to GitHub. Try again later.")
        }
        if (!response.ok) return Sums.Error("Could not read the release checksums (HTTP ${response.code}).")
        return Sums.Ok(Releases.parseChecksums(response.body.decodeToString()))
    }

    /** Old downloads, partial or complete, are removed before a new one and once up to date. */
    private fun clearDownloads() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun failed(release: Release, message: String) = UpdateState.Failed(message, release)

    companion object {
        const val ReleasesUrl = "https://api.github.com/repos/FlappedEar/BetterDriver/releases?per_page=20"
        const val DayMillis = 24L * 60 * 60 * 1000
        const val OfflineRetryMillis = 60L * 60 * 1000
        /** Far above the app's size (tens of MB); release.yml builds one APK. */
        const val MaxApkBytes = 300L * 1024 * 1024
        private const val PartSuffix = ".part"
        private const val TooManyRequests = 429
        private const val Forbidden = 403
        private const val MillisPerSecond = 1000L

        private val ApiHeaders = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )

        private val RateLimited = UpdateState.Failed("GitHub's limit for update checks is reached. Try again later.")

        private fun isRateLimited(r: Response) = r.code == TooManyRequests ||
            (r.code == Forbidden && (r.headers["x-ratelimit-remaining"] == "0" || "retry-after" in r.headers))

        /** From retry-after (seconds) or x-ratelimit-reset (epoch seconds); an hour if neither. */
        private fun rateLimitEnd(r: Response, now: Long): Long {
            r.headers["retry-after"]?.toLongOrNull()?.let { return now + it * MillisPerSecond }
            r.headers["x-ratelimit-reset"]?.toLongOrNull()?.let { return maxOf(now, it * MillisPerSecond) }
            return now + OfflineRetryMillis
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
