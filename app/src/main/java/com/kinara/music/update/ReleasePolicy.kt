/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.update

import org.json.JSONObject
import java.net.URI

internal const val MAX_APK_BYTES = 256L * 1024 * 1024

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(text: String): ReleaseVersion? {
            val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matchEntire(text) ?: return null
            val values = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(values[0], values[1], values[2])
        }
    }
}

data class UpdateRelease(val version: ReleaseVersion, val notes: String, val pageUrl: String,
    val apkUrl: String, val bytes: Long, val sha256: String)

/**
 * 公共 GitHub 反代前缀（path 前缀式：镜像地址 = 前缀 + 原始地址）。
 *
 * 只在直连 github.com 失败时按顺序尝试，解决"国内连不上 GitHub 就没法更新"。
 * 安全性并不依赖这些镜像：元数据仍必须匹配 html_url、资源名与 SHA-256，
 * 落地前还要求安装包**签名与已安装应用一致**（validateApkIdentity），
 * 镜像即使返回伪造的元数据也换不出一个能覆盖安装的包。
 */
internal val UPDATE_MIRRORS = listOf(
    "https://gh-proxy.com/",
    "https://ghfast.top/",
    "https://ghproxy.net/",
)

/** Only official release assets, not Actions artifacts, are eligible for installation. */
internal class ReleasePolicy(val repository: String) {
    init { require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) }
    val pageUrl = "https://github.com/$repository/releases"
    val apiUrl = "https://api.github.com/repos/$repository/releases/latest"

    /** 检查更新的地址：直连优先，失败后依次试反代。 */
    val apiTargets: List<String> get() = listOf(apiUrl) + UPDATE_MIRRORS.map { it + apiUrl }

    /** 下载地址：直连优先，失败后依次试反代。 */
    fun downloadTargets(apkUrl: String): List<String> = listOf(apkUrl) + UPDATE_MIRRORS.map { it + apkUrl }

    fun parse(body: String, installed: ReleaseVersion): UpdateRelease? {
        val release = JSONObject(body)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        val version = ReleaseVersion.parse(tag) ?: error("发布版本号格式不正确")
        if (version <= installed) return null
        val expectedPage = "$pageUrl/tag/$tag"
        check(release.optString("html_url") == expectedPage) { "更新来源不匹配" }
        val expectedName = "Kinara-$version-arm64-v8a.apk"
        val assets = release.optJSONArray("assets") ?: error("该版本尚未提供安装包")
        val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == expectedName && it.optString("state") == "uploaded" }
        check(candidates.size == 1) { "该版本尚未提供适用的正式安装包" }
        val asset = candidates.single()
        val expectedUrl = "$pageUrl/download/$tag/$expectedName"
        check(asset.optString("browser_download_url") == expectedUrl && trustedDownloadUrl(expectedUrl)) { "安装包地址不受信任" }
        val digest = asset.optString("digest")
        check(Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) { "该版本缺少 SHA-256 校验信息" }
        val size = asset.optLong("size", -1)
        check(size in 1..MAX_APK_BYTES) { "安装包大小异常" }
        return UpdateRelease(version, release.optString("body").take(12_000), expectedPage, expectedUrl, size, digest.substring(7).lowercase())
    }

    /**
     * 允许两种形态：直连 GitHub 的正式资源地址，或"已知反代前缀 + 该地址"。
     * 反代形态会剥掉前缀后按**完全相同**的规则再校验一次内层地址，
     * 所以前缀只是个传输通道，不会放宽对目标地址的要求。
     */
    fun trustedDownloadUrl(value: String): Boolean =
        canonicalAsset(value) || UPDATE_MIRRORS.any { prefix ->
            value.startsWith(prefix) && canonicalAsset(value.removePrefix(prefix))
        }

    private fun canonicalAsset(value: String): Boolean = runCatching {
        val url = URI(value)
        url.scheme == "https" && url.rawUserInfo == null && url.rawFragment == null && url.port in listOf(-1, 443) &&
            when (url.host?.lowercase()) {
                "github.com" -> url.rawPath.startsWith("/$repository/releases/download/") && url.normalize().rawPath == url.rawPath
                "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
                else -> false
            }
    }.getOrDefault(false)
}

internal fun validateApkIdentity(expected: UpdateRelease, packageName: String?, installedPackage: String,
    versionName: String?, versionCode: Long, installedVersionCode: Long, signatures: Set<String>, installedSignatures: Set<String>) {
    check(packageName == installedPackage) { "安装包不是 Kinara" }
    check(ReleaseVersion.parse(versionName.orEmpty()) == expected.version && versionCode > installedVersionCode) { "安装包版本与更新信息不符" }
    check(signatures.isNotEmpty() && signatures == installedSignatures) { "安装包签名与当前应用不一致，无法覆盖安装" }
}
