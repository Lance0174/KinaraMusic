package com.kinara.music.update

import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatePolicyTest {
    private val policy = ReleasePolicy("Lance0174/KinaraMusic")
    private val bytes = ByteArray(96_000) { (it % 251).toByte() }
    private val digest = digestHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private val current = ReleaseVersion(1, 4, 0)
    private fun fixture() = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v1.5.0")
        .put("html_url", "${policy.pageUrl}/tag/v1.5.0").put("body", "下一版更新")
        .put("assets", JSONArray().put(JSONObject().put("name", "Kinara-1.5.0-arm64-v8a.apk").put("state", "uploaded")
            .put("browser_download_url", "${policy.pageUrl}/download/v1.5.0/Kinara-1.5.0-arm64-v8a.apk")
            .put("digest", "sha256:$digest").put("size", bytes.size)))
    private fun release() = policy.parse(fixture().toString(), current)!!

    @Test fun versionsCompareNumericallyAndOnlyStableReleasesQualify() {
        assertTrue(ReleaseVersion.parse("v1.10.0")!! > ReleaseVersion.parse("1.9.99")!!)
        assertTrue(ReleaseVersion.parse("2.0.0")!! > ReleaseVersion.parse("1.99.99")!!)
        listOf("1.2", "1.2.3-beta", "1.02.3", "1.2.3.4", "2147483648.0.0", "v1.2.3/evil").forEach { assertNull(ReleaseVersion.parse(it)) }
        assertEquals(ReleaseVersion(1, 5, 0), release().version)
        assertNull(policy.parse(fixture().put("draft", true).toString(), current))
        assertNull(policy.parse(fixture().put("prerelease", true).toString(), current))
        assertNull(policy.parse(fixture().toString(), ReleaseVersion(1, 5, 0)))
        assertNull(policy.parse(fixture().toString(), ReleaseVersion(2, 0, 0)))
    }

    @Test fun updateRequiresAnExactRepositoryAssetAndDigest() {
        fun rejected(change: (JSONObject) -> Unit) {
            val json = fixture(); change(json)
            assertThrows(IllegalStateException::class.java) { policy.parse(json.toString(), current) }
        }
        rejected { it.put("html_url", "https://github.com/other/repo/releases/tag/v1.5.0") }
        rejected { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.test/app.apk") }
        rejected { it.getJSONArray("assets").getJSONObject(0).put("name", "app-release-unsigned.apk") }
        rejected { it.getJSONArray("assets").getJSONObject(0).put("digest", "") }
        rejected { it.getJSONArray("assets").getJSONObject(0).put("size", MAX_APK_BYTES + 1) }
        rejected { it.getJSONArray("assets").put(it.getJSONArray("assets").getJSONObject(0)) }
        assertTrue(policy.trustedDownloadUrl(release().apkUrl))
        assertTrue(policy.trustedDownloadUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123?signature=example"))
        listOf("http://github.com/Lance0174/KinaraMusic/releases/download/x/a.apk", "https://github.com.evil.test/file",
            "https://user:password@github.com/Lance0174/KinaraMusic/releases/download/x/a.apk",
            "https://github.com:444/Lance0174/KinaraMusic/releases/download/x/a.apk",
            "https://github.com/other/repo/releases/download/x/a.apk", "https://127.0.0.1/app.apk",
            "https://github.com/Lance0174/KinaraMusic/releases/download/../../settings",
            "https://release-assets.githubusercontent.com/file#fragment").forEach { assertFalse(it, policy.trustedDownloadUrl(it)) }
    }

    @Test fun streamedDownloadsRejectCorruptionTruncationOversizeAndCancellation() {
        val folder = RuntimeEnvironment.getApplication().cacheDir
        val output = File(folder, "update-test.part")
        var progress = 0L
        copyUpdate(ByteArrayInputStream(bytes), output, release(), { false }, { progress = it })
        assertEquals(bytes.size.toLong(), progress); assertArrayEquals(bytes, output.readBytes())
        output.delete()
        listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1), bytes.copyOf().also { it[42] = 0 }).forEach { invalid ->
            assertThrows(IllegalStateException::class.java) { copyUpdate(ByteArrayInputStream(invalid), output, release(), { false }, {}) }
            assertFalse(output.exists())
        }
        var calls = 0
        assertThrows(CancellationException::class.java) { copyUpdate(ByteArrayInputStream(bytes), output, release(), { ++calls > 1 }, {}) }
        assertFalse(output.exists())
    }

    @Test fun packageNameVersionAndInstalledSigningKeyAreAllRequired() {
        val expected = release()
        validateApkIdentity(expected, "com.kinara.music", "com.kinara.music", "1.5.0", 11, 10, setOf("key"), setOf("key"))
        assertThrows(IllegalStateException::class.java) { validateApkIdentity(expected, "evil.app", "com.kinara.music", "1.5.0", 11, 10, setOf("key"), setOf("key")) }
        assertThrows(IllegalStateException::class.java) { validateApkIdentity(expected, "com.kinara.music", "com.kinara.music", "1.5.0", 10, 10, setOf("key"), setOf("key")) }
        assertThrows(IllegalStateException::class.java) { validateApkIdentity(expected, "com.kinara.music", "com.kinara.music", "1.4.0", 11, 10, setOf("key"), setOf("key")) }
        assertThrows(IllegalStateException::class.java) { validateApkIdentity(expected, "com.kinara.music", "com.kinara.music", "1.5.0", 11, 10, setOf("other"), setOf("key")) }
        assertThrows(IllegalStateException::class.java) { validateApkIdentity(expected, "com.kinara.music", "com.kinara.music", "1.5.0", 11, 10, emptySet(), emptySet()) }
    }

    @Test fun fileProviderCannotExposeOtherPrivateFiles() {
        // AndroidX FileProvider uses Android '/' paths; Robolectric inherits Windows '\\'.
        // CI on Linux executes this real provider boundary test.
        org.junit.Assume.assumeTrue("FileProvider requires Android-style filesystem separators", File.separatorChar == '/')
        val app = RuntimeEnvironment.getApplication()
        val authority = "${app.packageName}.updates"
        val apk = File(app.cacheDir, "updates/Kinara-1.5.0.apk")
        val uri = FileProvider.getUriForFile(app, authority, apk)
        assertEquals("content", uri.scheme); assertEquals(authority, uri.authority)
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, authority, File(app.filesDir, "session")) }
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, authority, File(app.cacheDir, "updates/../private")) }
    }
}
