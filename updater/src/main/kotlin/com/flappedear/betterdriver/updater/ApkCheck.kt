package com.flappedear.betterdriver.updater

/**
 * The release signing certificate (FBBD-37). Installed release builds carry it, and a downloaded
 * APK must be signed with it and nothing else. The same value is pinned in release.yml, which
 * refuses to publish an APK signed with any other key; UpdaterTest checks the two match.
 */
object ReleaseCert {
    const val Sha256 = "0e12358c264f8fa04e448098cb1ed0302256a1e749018a2d2065fea17167ec05"
}

/** What Android's package manager reads from an APK file or from the installed app. */
data class ApkInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    /** SHA-256 of each signing certificate, lower-case hex. */
    val certSha256: Set<String>,
)

/** Checks a downloaded APK before Android's installer is offered. */
class ApkCheck(
    private val packageName: String,
    private val installedVersionCode: Long,
    private val pinnedCert: String = ReleaseCert.Sha256,
) {
    /** Null when [apk] may be installed as [release]; otherwise why not, for the user. */
    fun problem(apk: ApkInfo?, release: Version): String? = when {
        apk == null -> "The download is not an app Android can read."
        apk.packageName != packageName -> "The download is a different app (${apk.packageName})."
        apk.certSha256 != setOf(pinnedCert) -> "The download is not signed with the BetterDriver release key."
        apk.versionName != release.toString() -> "The download is version ${apk.versionName}, not $release."
        apk.versionCode <= installedVersionCode -> "The download is not newer than this build."
        else -> null
    }
}
