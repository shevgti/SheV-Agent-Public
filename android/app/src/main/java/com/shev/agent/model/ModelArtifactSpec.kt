package com.shev.agent.model

/**
 * Immutable identity for the optional on-device model.
 *
 * Keep the revision pinned: resolving "main" would make an APK update silently change
 * the model bytes and invalidate reproducibility and integrity checks.
 */
data class ModelArtifactSpec(
    val repository: String,
    val revision: String,
    val fileName: String,
    val sha256: String,
    val displaySize: String,
    val minimumRamGb: Int,
    val license: String
) {
    init {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        require(revision.matches(Regex("[0-9a-f]{40}"))) { "Model revision must be a pinned commit SHA." }
        require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") ) && ".." !in fileName && fileName.endsWith(".litertlm")) { "Model filename must be a safe app-private filename." }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Expected SHA-256 must contain 64 lowercase hex characters." }
        require(displaySize.isNotBlank())
        require(minimumRamGb > 0)
        require(license.isNotBlank())
    }

    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$fileName"

    companion object {
        val GEMMA_4_E2B_IT = ModelArtifactSpec(
            repository = "litert-community/gemma-4-E2B-it-litert-lm",
            revision = "068a1bccf497c9be8c7f03c89d2be9dae44857e2",
            fileName = "gemma-4-E2B-it.litertlm",
            sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
            displaySize = "2.58 GB",
            minimumRamGb = 8,
            license = "Apache-2.0"
        )
    }
}
