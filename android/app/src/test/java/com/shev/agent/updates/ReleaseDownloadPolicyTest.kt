package com.shev.agent.updates

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ReleaseDownloadPolicyTest {
    @Test fun acceptsOfficialGithubAndReleaseAssetHosts() {
        listOf(
            "https://api.github.com/repos/example/releases/latest",
            "https://github.com/example/repo/releases/download/v1/app.apk",
            "https://release-assets.githubusercontent.com/assets/app.apk"
        ).forEach { assertEquals(it, ReleaseDownloadPolicy.validate(URL(it)).toString()) }
    }

    @Test fun rejectsHttpLookalikesAndUntrustedHosts() {
        listOf(
            "http://github.com/app.apk",
            "https://github.com.evil.example/app.apk",
            "https://evil-githubusercontent.com/app.apk",
            "https://evil.example/app.apk",
            "https://user@github.com/app.apk",
            "https://github.com:8443/app.apk"
        ).forEach { url ->
            try {
                ReleaseDownloadPolicy.validate(URL(url))
                fail("Expected URL to be rejected: $url")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
