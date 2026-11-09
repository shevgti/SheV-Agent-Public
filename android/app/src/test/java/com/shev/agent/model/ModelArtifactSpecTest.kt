package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelArtifactSpecTest {
    @Test
    fun gemmaArtifactUsesPinnedPublicRevisionAndIntegrityHash() {
        val artifact = ModelArtifactSpec.GEMMA_4_E2B_IT

        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", artifact.repository)
        assertEquals("068a1bccf497c9be8c7f03c89d2be9dae44857e2", artifact.revision)
        assertEquals("gemma-4-E2B-it.litertlm", artifact.fileName)
        assertEquals(
            "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
            artifact.sha256
        )
        assertEquals("Apache-2.0", artifact.license)
        assertEquals(8, artifact.minimumRamGb)
    }

    @Test
    fun downloadUrlCannotAccidentallyFollowMutableMain() {
        val artifact = ModelArtifactSpec.GEMMA_4_E2B_IT

        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/068a1bccf497c9be8c7f03c89d2be9dae44857e2/gemma-4-E2B-it.litertlm",
            artifact.downloadUrl
        )
        assertTrue(artifact.downloadUrl.contains(artifact.revision))
        assertTrue(!artifact.downloadUrl.contains("/resolve/main/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mutableRevisionIsRejected() {
        ModelArtifactSpec(
            repository = "owner/model",
            revision = "main",
            fileName = "model.litertlm",
            sha256 = "a".repeat(64),
            displaySize = "1 GB",
            minimumRamGb = 8,
            license = "Apache-2.0"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedIntegrityHashIsRejected() {
        ModelArtifactSpec(
            repository = "owner/model",
            revision = "068a1bccf497c9be8c7f03c89d2be9dae44857e2",
            fileName = "model.litertlm",
            sha256 = "not-a-hash",
            displaySize = "1 GB",
            minimumRamGb = 8,
            license = "Apache-2.0"
        )
    }
    @Test(expected = IllegalArgumentException::class)
    fun pathTraversalFilenameIsRejected() {
        ModelArtifactSpec(
            repository = "owner/model",
            revision = "068a1bccf497c9be8c7f03c89d2be9dae44857e2",
            fileName = "../outside.litertlm",
            sha256 = "a".repeat(64),
            displaySize = "1 GB",
            minimumRamGb = 8,
            license = "Apache-2.0"
        )
    }

}
