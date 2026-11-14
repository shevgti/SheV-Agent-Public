package com.shev.agent.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseManifestParserTest {
    @Test fun parsesExplicitAppVersionAndVersionCodeFromUniqueBuildTag() {
        val release = release(
            tag = "v0.3.2-build.33",
            body = "SheV Agent update.\n\nversionName: 0.3.2\nversionCode: 33"
        )

        val parsed = ReleaseManifestParser.parse(release, currentVersionCode = 32)

        assertEquals(ReleaseInfo(
            version = "0.3.2",
            versionCode = 33,
            apkUrl = "https://github.com/shevgti/SheV-Agent-Public/releases/download/v0.3.2-build.33/app-debug.apk",
            checksumUrl = "https://github.com/shevgti/SheV-Agent-Public/releases/download/v0.3.2-build.33/app-debug.apk.sha256"
        ), parsed)
    }

    @Test fun supportsLegacyReleaseWithoutExplicitVersionName() {
        val parsed = ReleaseManifestParser.parse(
            release(tag = "v0.3.1", body = "versionCode: 32"),
            currentVersionCode = 31
        )

        assertEquals("0.3.1", parsed?.version)
        assertEquals(32, parsed?.versionCode)
    }

    @Test fun repeatedAppVersionNamesRemainDistinctByVersionCode() {
        val first = ReleaseManifestParser.parse(
            release("v0.3.2-build.33", "versionName: 0.3.2\nversionCode: 33"),
            currentVersionCode = 32
        )
        val second = ReleaseManifestParser.parse(
            release("v0.3.2-build.34", "versionName: 0.3.2\nversionCode: 34"),
            currentVersionCode = 33
        )

        assertEquals("0.3.2", first?.version)
        assertEquals("0.3.2", second?.version)
        assertEquals(33, first?.versionCode)
        assertEquals(34, second?.versionCode)
    }

    @Test fun returnsNullWhenReleaseIsNotNewerThanInstalledBuild() {
        assertNull(ReleaseManifestParser.parse(
            release("v0.3.2-build.33", "versionName: 0.3.2\nversionCode: 33"),
            currentVersionCode = 33
        ))
    }

    @Test fun rejectsReleaseMissingChecksumAsset() {
        val release = release(
            tag = "v0.3.2-build.33",
            body = "versionName: 0.3.2\nversionCode: 33",
            includeChecksum = false
        )

        assertThrows(IllegalStateException::class.java) {
            ReleaseManifestParser.parse(release, currentVersionCode = 32)
        }
    }

    @Test fun rejectsMalformedOrMissingVersionCode() {
        listOf("versionCode: zero", "versionName: 0.3.2", "versionCode: -1").forEach { body ->
            assertThrows(IllegalStateException::class.java) {
                ReleaseManifestParser.parse(
                    release("v0.3.2-build.33", body),
                    currentVersionCode = 32
                )
            }
        }
    }

    private fun release(
        tag: String,
        body: String,
        includeChecksum: Boolean = true
    ) = JSONObject().apply {
        put("tag_name", tag)
        put("body", body)
        put("draft", false)
        put("prerelease", false)
        val assets = JSONArray().put(
            JSONObject()
                .put("name", "app-debug.apk")
                .put("browser_download_url", "https://github.com/shevgti/SheV-Agent-Public/releases/download/$tag/app-debug.apk")
        )
        if (includeChecksum) {
            assets.put(
                JSONObject()
                    .put("name", "app-debug.apk.sha256")
                    .put("browser_download_url", "https://github.com/shevgti/SheV-Agent-Public/releases/download/$tag/app-debug.apk.sha256")
            )
        }
        put("assets", assets)
    }
}
