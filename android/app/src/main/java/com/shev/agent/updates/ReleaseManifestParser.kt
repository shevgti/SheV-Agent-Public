package com.shev.agent.updates

import org.json.JSONObject

/** Parses the public release contract shared by manual checks and background notifications. */
internal object ReleaseManifestParser {
    private const val APK_ASSET = "app-debug.apk"
    private const val CHECKSUM_ASSET = "app-debug.apk.sha256"

    fun parse(release: JSONObject, currentVersionCode: Int): ReleaseInfo? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null

        val tag = release.optString("tag_name").trim()
        check(tag.isNotBlank()) { "Релиз не содержит номера сборки." }
        val body = release.optString("body")

        val versionCode = Regex("""(?im)^\s*versionCode\s*:\s*(\d+)\s*$""")
            .find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("В описании релиза отсутствует корректный versionCode.")
        check(versionCode > 0) { "versionCode релиза должен быть положительным." }
        if (versionCode <= currentVersionCode) return null

        val version = Regex("""(?im)^\s*versionName\s*:\s*([0-9A-Za-z.+_-]+)\s*$""")
            .find(body)?.groupValues?.get(1)
            ?: tag.removePrefix("v")
        check(version.isNotBlank()) { "Релиз не содержит versionName." }

        val assets = release.optJSONArray("assets")
            ?: error("В релизе отсутствует список файлов.")
        var apkUrl: String? = null
        var checksumUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            when (asset.optString("name")) {
                APK_ASSET -> apkUrl = asset.optString("browser_download_url")
                CHECKSUM_ASSET -> checksumUrl = asset.optString("browser_download_url")
            }
        }
        check(!apkUrl.isNullOrBlank()) { "В релизе отсутствует APK $APK_ASSET." }
        check(!checksumUrl.isNullOrBlank()) { "В релизе отсутствует контрольная сумма SHA-256." }

        return ReleaseInfo(version, versionCode, apkUrl!!, checksumUrl!!)
    }
}
