package com.flappedear.betterdriver.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A published release that carries an APK and a checksum file, as release.yml makes them. */
data class Release(
    val version: Version,
    val apk: Asset,
    val checksums: Asset,
    val pageUrl: String,
)

data class Asset(val name: String, val size: Long, val url: String)

/** Reading the GitHub Releases API and the release's SHA256SUMS.txt. */
object Releases {
    /** Release asset names from release.yml. */
    fun apkName(version: Version) = "BetterDriver-$version-android.apk"
    const val ChecksumsName = "SHA256SUMS.txt"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ApiRelease(
        @SerialName("tag_name") val tag: String = "",
        val draft: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<ApiAsset> = emptyList(),
    )

    @Serializable
    private data class ApiAsset(
        val name: String = "",
        val size: Long = 0,
        @SerialName("browser_download_url") val url: String = "",
    )

    /**
     * The newest release in a `GET /repos/{owner}/{repo}/releases` answer, pre-releases included
     * (every 0.x release is one). Drafts, tags that are not vX.Y.Z and releases without both the
     * APK and SHA256SUMS.txt over HTTPS are skipped. Null when none is left.
     *
     * @throws IllegalArgumentException when the answer is not a list of releases.
     */
    fun newest(body: String): Release? {
        val list = try {
            json.decodeFromString<List<ApiRelease>>(body)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a release list", e)
        }
        return list.mapNotNull(::toRelease).maxByOrNull { it.version }
    }

    @Suppress("ReturnCount") // One early return per kind of unusable release.
    private fun toRelease(r: ApiRelease): Release? {
        if (r.draft) return null
        val version = Version.parse(r.tag)?.takeIf { r.tag.startsWith("v") } ?: return null
        fun asset(name: String) = r.assets.firstOrNull { it.name == name && it.url.startsWith("https://") }
            ?.let { Asset(it.name, it.size, it.url) }
        val apk = asset(apkName(version))?.takeIf { it.size > 0 } ?: return null
        val sums = asset(ChecksumsName) ?: return null
        return Release(version, apk, sums, r.htmlUrl)
    }

    /**
     * SHA-256 by file name from `sha256sum` output: "<64 hex>  <name>" per line, or "<64 hex> *<name>"
     * in binary mode. Other lines are ignored. Hashes are lower case.
     */
    fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { ChecksumLine.matchEntire(it.trimEnd()) }
        .associate { m -> m.groupValues[2] to m.groupValues[1].lowercase() }

    private val ChecksumLine = Regex("""([0-9a-fA-F]{64}) [ *](\S.*)""")
}
