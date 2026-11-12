/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新源的反代支持。
 *
 * 这里同时锁住"能用"和"不会因此被降级安全性"两件事：
 * 反代只是传输通道，内层地址必须仍然通过完全相同的严格校验；
 * 前缀不在白名单里、或者内层指向别处，一律拒绝。
 */
class UpdateMirrorTest {

    private val policy = ReleasePolicy("example/Kinara")
    private val apkUrl = "https://github.com/example/Kinara/releases/download/v1.4.5/Kinara-1.4.5-arm64-v8a.apk"

    @Test
    fun `direct github asset url is trusted`() {
        assertTrue(policy.trustedDownloadUrl(apkUrl))
    }

    @Test
    fun `every configured mirror prefix is accepted for a valid asset url`() {
        UPDATE_MIRRORS.forEach { prefix ->
            assertTrue("$prefix 应当被接受", policy.trustedDownloadUrl(prefix + apkUrl))
        }
    }

    @Test
    fun `a mirror prefix cannot smuggle a foreign target`() {
        val prefix = UPDATE_MIRRORS.first()
        assertFalse(policy.trustedDownloadUrl(prefix + "https://evil.example/Kinara.apk"))
        assertFalse(policy.trustedDownloadUrl(prefix + "https://github.com/other/Repo/releases/download/v1/Kinara.apk"))
    }

    @Test
    fun `an unknown prefix is rejected even when the inner url is valid`() {
        assertFalse(policy.trustedDownloadUrl("https://evil.example/" + apkUrl))
        assertFalse(policy.trustedDownloadUrl("http://" + apkUrl.removePrefix("https://")))
    }

    @Test
    fun `update targets try github first and then the mirrors`() {
        assertEquals(policy.apiUrl, policy.apiTargets.first())
        assertEquals(UPDATE_MIRRORS.size + 1, policy.apiTargets.size)
        UPDATE_MIRRORS.forEachIndexed { index, prefix ->
            assertEquals(prefix + policy.apiUrl, policy.apiTargets[index + 1])
        }
    }

    @Test
    fun `download targets try github first and then the mirrors`() {
        val targets = policy.downloadTargets(apkUrl)
        assertEquals(apkUrl, targets.first())
        assertEquals(UPDATE_MIRRORS.size + 1, targets.size)
        // 每个候选都必须过同一道校验，避免"候选列表里混进不可信地址"。
        targets.forEach { assertTrue(it, policy.trustedDownloadUrl(it)) }
    }
}
