package com.flappedear.betterdriver.updater

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Package = "com.flappedear.betterdriver"
private const val Downloads = "https://github.com/FlappedEar/BetterDriver/releases/download/v0.2.0"
private const val ApkUrl = "$Downloads/BetterDriver-0.2.0-android.apk"
private const val SumsUrl = "$Downloads/SHA256SUMS.txt"

/** Answers by URL and records every call. */
private class FakeNetwork : Network {
    val calls = mutableListOf<String>()
    val answers = mutableMapOf<String, () -> Response>()
    var offline = false

    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): Response {
        calls += url
        if (offline) throw IOException("offline")
        return answers[url]?.invoke() ?: Response(404)
    }

    @Suppress("ReturnCount")
    override fun download(url: String, out: OutputStream, maxBytes: Long, keepGoing: (Long) -> Boolean): Response {
        calls += url
        if (offline) throw IOException("offline")
        val r = answers[url]?.invoke() ?: return Response(404)
        if (!r.ok) return r
        var written = 0L
        r.body.toList().chunked(4).forEach { chunk ->
            if (written + chunk.size > maxBytes) throw IOException("too large")
            out.write(chunk.toByteArray())
            written += chunk.size
            if (!keepGoing(written)) throw IOException("cancelled")
        }
        return r
    }
}

private class MemoryLog : CheckLog {
    override var nextAutoCheck = 0L
    override var rateLimitedUntil = 0L
}

private fun sha(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun releasesJson(vararg releases: String) = releases.joinToString(",", "[", "]")

private fun release(tag: String, version: String = tag.removePrefix("v"), draft: Boolean = false, size: Long = 8) = """
    {"tag_name":"$tag","draft":$draft,"prerelease":true,"html_url":"https://github.com/FlappedEar/BetterDriver/releases/tag/$tag",
     "assets":[
       {"name":"BetterDriver-$version-android.apk","size":$size,
        "browser_download_url":"https://github.com/FlappedEar/BetterDriver/releases/download/$tag/BetterDriver-$version-android.apk"},
       {"name":"SHA256SUMS.txt","size":100,
        "browser_download_url":"https://github.com/FlappedEar/BetterDriver/releases/download/$tag/SHA256SUMS.txt"}
     ]}
""".trimIndent()

class UpdaterTest {
    private val dir: File = createTempDirectory("updater").toFile()
    private val net = FakeNetwork()
    private val log = MemoryLog()
    private var now = 1_000_000_000_000L
    private var recording = false
    private val apkBytes = "APKBYTES".toByteArray()
    private var apkInfo: ApkInfo? = ApkInfo(Package, "0.2.0", 120, setOf(ReleaseCert.Sha256))

    private val updater = Updater(
        network = net,
        current = Version(0, 1, 3),
        dir = dir,
        inspect = { apkInfo },
        apkCheck = ApkCheck(Package, installedVersionCode = 100),
        busy = { recording },
        log = log,
        clock = { now },
    )

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun serveReleases(vararg releases: String) {
        net.answers[Updater.ReleasesUrl] = { Response(200, body = releasesJson(*releases).toByteArray()) }
    }

    private fun serveDownload(
        apk: ByteArray = apkBytes,
        sums: String = "${sha(apkBytes)}  BetterDriver-0.2.0-android.apk\n",
    ) {
        net.answers[ApkUrl] = { Response(200, body = apk) }
        net.answers[SumsUrl] = { Response(200, body = sums.toByteArray()) }
    }

    private fun available(): Release {
        serveReleases(release("v0.2.0"))
        return assertIs<UpdateState.Available>(updater.check()).release
    }

    @Test
    fun `versions compare by number, not text`() {
        assertTrue(Version.parse("0.10.0")!! > Version.parse("0.9.9")!!)
        assertTrue(Version.parse("v1.0.0")!! > Version.parse("0.99.99")!!)
        assertEquals(Version(0, 1, 3), Version.parse("v0.1.3"))
        assertNull(Version.parse("0.1"))
        assertNull(Version.parse("0.1.3-rc1"))
    }

    @Test
    fun `newest usable release wins, pre-releases included`() {
        val newest = Releases.newest(
            releasesJson(
                release("v0.1.3"),
                release("v0.3.0", draft = true),
                release("nightly", version = "0.9.0"),
                release("v0.2.0"),
                // No APK of its own version: skipped.
                release("v0.2.5", version = "0.2.4"),
            ),
        )
        assertEquals(Version(0, 2, 0), newest?.version)
        assertEquals(ApkUrl, newest?.apk?.url)
        assertEquals(SumsUrl, newest?.checksums?.url)
    }

    @Test
    fun `a release without checksums is not offered`() {
        val noSums = """{"tag_name":"v0.2.0","assets":[{"name":"BetterDriver-0.2.0-android.apk","size":8,
            "browser_download_url":"$ApkUrl"}]}"""
        assertNull(Releases.newest("[$noSums]"))
    }

    @Test
    fun `checksum file lines in text and binary mode`() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = Releases.parseChecksums("$a  BetterDriver-0.2.0-android.apk\n$b *other.apk\nnot a line\n")
        assertEquals(mapOf("BetterDriver-0.2.0-android.apk" to a, "other.apk" to b.lowercase()), sums)
    }

    @Test
    fun `newer release is offered, same or older is up to date`() {
        assertEquals(Version(0, 2, 0), available().version)
        serveReleases(release("v0.1.3"), release("v0.1.2"))
        assertEquals(UpdateState.UpToDate, updater.check())
    }

    @Test
    fun `launch check runs at most once a day`() {
        serveReleases(release("v0.1.3"))
        assertEquals(UpdateState.UpToDate, updater.checkIfDue())
        now += Updater.DayMillis - 1
        assertNull(updater.checkIfDue())
        assertEquals(1, net.calls.size)
        now += 1
        assertEquals(UpdateState.UpToDate, updater.checkIfDue())
        // "Check for updates" is not limited to once a day.
        assertEquals(UpdateState.UpToDate, updater.check())
        assertEquals(3, net.calls.size)
    }

    @Test
    fun `offline is a short message and retried later`() {
        net.offline = true
        assertIs<UpdateState.Failed>(updater.check())
        assertNull(updater.checkIfDue())
        now += Updater.OfflineRetryMillis
        net.offline = false
        serveReleases(release("v0.1.3"))
        assertEquals(UpdateState.UpToDate, updater.checkIfDue())
    }

    @Test
    fun `rate limited stops calls until GitHub's reset time`() {
        val reset = now / 1000 + 600
        net.answers[Updater.ReleasesUrl] = {
            Response(403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$reset"))
        }
        val failed = assertIs<UpdateState.Failed>(updater.check())
        assertTrue("limit" in failed.message)
        assertIs<UpdateState.Failed>(updater.check())
        assertEquals(1, net.calls.size)
        now = reset * 1000
        serveReleases(release("v0.1.3"))
        assertEquals(UpdateState.UpToDate, updater.check())
        assertEquals(2, net.calls.size)
    }

    @Test
    fun `429 with retry-after is rate limited too`() {
        net.answers[Updater.ReleasesUrl] = { Response(429, mapOf("retry-after" to "60")) }
        assertIs<UpdateState.Failed>(updater.check())
        assertEquals(now + 60_000, log.rateLimitedUntil)
    }

    @Test
    fun `unexpected answer fails without crashing`() {
        net.answers[Updater.ReleasesUrl] = { Response(200, body = "{\"message\":\"nope\"}".toByteArray()) }
        assertIs<UpdateState.Failed>(updater.check())
        net.answers[Updater.ReleasesUrl] = { Response(500) }
        assertIs<UpdateState.Failed>(updater.check())
    }

    @Test
    fun `download that matches its checksum and certificate is ready`() {
        val release = available()
        serveDownload()
        val ready = assertIs<UpdateState.Ready>(updater.download(release))
        assertEquals(apkBytes.toList(), ready.apk.readBytes().toList())
        assertEquals(listOf(ready.apk), dir.listFiles()!!.toList())
    }

    @Test
    fun `checksum mismatch deletes the file`() {
        val release = available()
        serveDownload(apk = "EVILBYTE".toByteArray())
        val failed = assertIs<UpdateState.Failed>(updater.download(release))
        assertTrue("checksum" in failed.message)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `no checksum for the APK means no download`() {
        val release = available()
        serveDownload(sums = "${"0".repeat(64)}  something-else.apk\n")
        assertIs<UpdateState.Failed>(updater.download(release))
        assertTrue(ApkUrl !in net.calls)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `wrong size deletes the file`() {
        serveReleases(release("v0.2.0", size = 9))
        val release = assertIs<UpdateState.Available>(updater.check()).release
        serveDownload()
        assertIs<UpdateState.Failed>(updater.download(release))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `an oversized release APK is not downloaded`() {
        serveReleases(release("v0.2.0", size = Updater.MaxApkBytes + 1))
        val release = assertIs<UpdateState.Available>(updater.check()).release
        serveDownload()
        assertIs<UpdateState.Failed>(updater.download(release))
        assertTrue(ApkUrl !in net.calls && SumsUrl !in net.calls)
    }

    @Test
    fun `APK signed with another key is deleted`() {
        val release = available()
        serveDownload()
        apkInfo = ApkInfo(Package, "0.2.0", 120, setOf("f".repeat(64)))
        val failed = assertIs<UpdateState.Failed>(updater.download(release))
        assertTrue("release key" in failed.message)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `APK check covers package, version and build number`() {
        val check = ApkCheck(Package, installedVersionCode = 100)
        val good = ApkInfo(Package, "0.2.0", 120, setOf(ReleaseCert.Sha256))
        val v = Version(0, 2, 0)
        assertNull(check.problem(good, v))
        assertNotNull(check.problem(null, v))
        assertNotNull(check.problem(good.copy(packageName = "com.other"), v))
        assertNotNull(check.problem(good.copy(versionName = "0.1.9"), v))
        assertNotNull(check.problem(good.copy(versionCode = 100), v))
        assertNotNull(check.problem(good.copy(certSha256 = setOf(ReleaseCert.Sha256, "f".repeat(64))), v))
    }

    @Test
    fun `no update work while a session records`() {
        recording = true
        serveReleases(release("v0.2.0"))
        serveDownload()
        assertEquals(UpdateState.Paused, updater.checkIfDue())
        assertEquals(UpdateState.Paused, updater.check())
        val release = Releases.newest(releasesJson(release("v0.2.0")))!!
        assertEquals(UpdateState.Paused, updater.download(release))
        assertTrue(net.calls.isEmpty())
        assertTrue(dir.listFiles().isNullOrEmpty())
        assertTrue(!updater.mayInstall())
    }

    @Test
    fun `a session starting mid-download stops it and leaves no file`() {
        val release = available()
        serveDownload()
        val state = updater.download(release) { bytes -> if (bytes >= 4) recording = true }
        assertEquals(UpdateState.Paused, state)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a session starting during the checksum fetch stops before the APK request`() {
        val release = available()
        serveDownload()
        val sums = net.answers.getValue(SumsUrl)
        net.answers[SumsUrl] = {
            recording = true
            sums()
        }
        assertEquals(UpdateState.Paused, updater.download(release))
        assertTrue(ApkUrl !in net.calls)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `pinned certificate matches release yml`() {
        val workflow = File("../.github/workflows/release.yml").readText()
        assertTrue("RELEASE_CERT_SHA256: ${ReleaseCert.Sha256}" in workflow)
    }
}
