package com.shev.agent.model

import java.net.URL
import java.util.Locale

/** Hugging Face model downloads may redirect to its official HF CDN domains only. */
internal object ModelDownloadUrlPolicy {
    private val trustedSuffixes = setOf("huggingface.co", "hf.co")

    fun validate(url: URL): URL {
        val protocol = url.protocol.lowercase(Locale.ROOT)
        val host = url.host.lowercase(Locale.ROOT)
        val trustedHost = trustedSuffixes.any { host == it || host.endsWith(".$it") }
        require(protocol == "https" && trustedHost && url.userInfo == null &&
            (url.port == -1 || url.port == 443)) {
            "Недоверенный адрес загрузки модели."
        }
        return url
    }
}
