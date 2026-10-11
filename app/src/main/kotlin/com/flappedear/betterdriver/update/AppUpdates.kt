package com.flappedear.betterdriver.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.flappedear.betterdriver.BuildConfig
import com.flappedear.betterdriver.updater.ApkCheck
import com.flappedear.betterdriver.updater.ApkInfo
import com.flappedear.betterdriver.updater.CheckLog
import com.flappedear.betterdriver.updater.ReleaseCert
import com.flappedear.betterdriver.updater.UpdateState
import com.flappedear.betterdriver.updater.Updater
import com.flappedear.betterdriver.updater.Version
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The updater for the UI process (FBBD-73, FBBD-74). Work runs on one background thread; [state]
 * is Compose state. Until the UI reports that no session is recording or unsaved ([setBusy]), it
 * counts as busy and does nothing.
 *
 * Only a build signed with the release key can install a release over itself, so debug and CI
 * builds never check ([enabled] is false).
 */
class AppUpdates private constructor(private val context: Context) {

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** True when this build is signed with the release key, so releases install over it. */
    val enabled: Boolean = installedInfo()?.certSha256 == setOf(ReleaseCert.Sha256)

    /** Read on the worker thread, written from the UI. */
    @Volatile
    private var busy = true

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "updates") }
    private val main = Handler(Looper.getMainLooper())

    private val updater by lazy {
        val prefs = context.getSharedPreferences(PrefsFile, Context.MODE_PRIVATE)
        Updater(
            network = HttpNetwork("BetterDriver/${BuildConfig.VERSION_NAME} (Android)"),
            current = Version.parse(BuildConfig.VERSION_NAME) ?: Version(0, 0, 0),
            dir = File(context.cacheDir, UpdatesDir),
            inspect = ::archiveInfo,
            apkCheck = ApkCheck(context.packageName, BuildConfig.VERSION_CODE.toLong()),
            busy = { busy },
            log = object : CheckLog {
                override var nextAutoCheck: Long
                    get() = prefs.getLong(KeyNextCheck, 0L)
                    set(value) = prefs.edit().putLong(KeyNextCheck, value).apply()
                override var rateLimitedUntil: Long
                    get() = prefs.getLong(KeyRateLimited, 0L)
                    set(value) = prefs.edit().putLong(KeyRateLimited, value).apply()
            },
        )
    }

    /** A session is recording, starting or not saved yet. Starting one stops a download. */
    fun setBusy(value: Boolean) {
        busy = value
    }

    /** On launch and after a session: at most once a day. */
    fun checkIfDue() {
        if (!enabled || state != UpdateState.Idle && state !is UpdateState.UpToDate) return
        submit { updater.checkIfDue() }
    }

    /** "Check for updates". */
    fun checkNow() {
        if (!enabled || state is UpdateState.Checking || state is UpdateState.Downloading) return
        state = UpdateState.Checking
        submit { updater.check() }
    }

    fun download() {
        val release = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        } ?: return
        state = UpdateState.Downloading(release, 0)
        submit {
            updater.download(release) { bytes ->
                main.post { if (state is UpdateState.Downloading) state = UpdateState.Downloading(release, bytes) }
            }
        }
    }

    /**
     * Opens Android's installer for the checked APK, never while a session records or is unsaved.
     * Without the "install unknown apps" permission it opens that setting first; the user comes
     * back and taps Install again.
     */
    @Suppress("ReturnCount") // Nothing ready, a session busy, or the file gone.
    fun install(activity: Activity) {
        val ready = state as? UpdateState.Ready ?: return
        if (!updater.mayInstall()) return
        if (!ready.apk.isFile) {
            // The system can clear the cache; download again.
            state = UpdateState.Available(ready.release)
            return
        }
        val pm = context.packageManager
        val intent = if (!pm.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}$AuthoritySuffix", ready.apk)
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ApkMimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            state = UpdateState.Failed("Android's installer could not be opened.", ready.release)
        }
    }

    /**
     * Runs [work] on the worker. A [UpdateState.Paused] result (a session started) keeps what was
     * shown before, except a download, which goes back to available.
     */
    private fun submit(work: () -> UpdateState?) = worker.execute {
        val result = work()
        main.post {
            state = when {
                result == null -> state
                result != UpdateState.Paused -> result
                else -> when (val s = state) {
                    is UpdateState.Downloading -> UpdateState.Available(s.release)
                    UpdateState.Checking -> UpdateState.Idle
                    else -> s
                }
            }
        }
    }

    private fun installedInfo(): ApkInfo? = try {
        context.packageManager.getPackageInfo(context.packageName, signatureFlags()).toApkInfo()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    // GET_SIGNATURES is the fallback for archives without signingInfo.
    @Suppress("DEPRECATION", "ReturnCount")
    private fun archiveInfo(file: File): ApkInfo? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.path, signatureFlags()) ?: return null
        val certs = info.toApkInfo()
        if (certs.certSha256.isNotEmpty()) return certs
        return pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)?.toApkInfo()
    }

    companion object {
        private const val PrefsFile = "updates"
        private const val KeyNextCheck = "next_check"
        private const val KeyRateLimited = "rate_limited_until"
        /** Must match res/xml/update_paths.xml. */
        private const val UpdatesDir = "updates"
        /** Must match the FileProvider in the manifest. */
        private const val AuthoritySuffix = ".updates"
        private const val ApkMimeType = "application/vnd.android.package-archive"

        @Volatile
        private var instance: AppUpdates? = null

        fun get(context: Context): AppUpdates = instance ?: synchronized(this) {
            instance ?: AppUpdates(context.applicationContext).also { instance = it }
        }

        @Suppress("DEPRECATION")
        private fun signatureFlags() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        @Suppress("DEPRECATION")
        private fun PackageInfo.toApkInfo(): ApkInfo {
            val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && signingInfo != null) {
                // The certificates that sign this APK now, without past ones from key rotation.
                signingInfo?.apkContentsSigners
            } else {
                signatures
            }
            val sha = MessageDigest.getInstance("SHA-256")
            return ApkInfo(
                packageName = packageName,
                versionName = versionName.orEmpty(),
                versionCode = PackageInfoCompat.getLongVersionCode(this),
                certSha256 = signers.orEmpty().map { s ->
                    sha.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
                }.toSet(),
            )
        }
    }
}
