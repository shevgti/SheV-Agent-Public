package com.shev.agent.model

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ModelDownloadUrlPolicyTest {
    @Test fun acceptsHuggingFaceAndOfficialCdnHosts() {
        listOf(
            "https://huggingface.co/org/model/resolve/abc/model.litertlm",
            "https://cdn-lfs.huggingface.co/file",
            "https://cas-bridge.xethub.hf.co/file"
        ).forEach { assertEquals(it, ModelDownloadUrlPolicy.validate(URL(it)).toString()) }
    }

    @Test fun rejectsUntrustedOrUnsafeModelUrls() {
        listOf(
            "http://huggingface.co/model",
            "https://huggingface.co.evil.example/model",
            "https://evil-hf.co/model",
            "https://evil.example/model",
            "https://user@huggingface.co/model",
            "https://huggingface.co:8443/model"
        ).forEach { url ->
            try {
                ModelDownloadUrlPolicy.validate(URL(url))
                fail("Expected URL to be rejected: $url")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
