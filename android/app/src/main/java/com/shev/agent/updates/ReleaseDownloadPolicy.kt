package com.shev.agent.updates

import java.net.URL
import java.util.Locale

/** Restricts release asset downloads and every redirect to GitHub-controlled HTTPS hosts. */
internal object ReleaseDownloadPolicy {
    private val exactHosts = setOf("github.com", "api.github.com")
    private val trustedSuffixes = setOf("github.com", "githubusercontent.com")

    fun validate(url: URL): URL {
        val protocol = url.protocol.lowercase(Locale.ROOT)
        val host = url.host.lowercase(Locale.ROOT)
        val trustedHost = exactHosts.contains(host) ||
            trustedSuffixes.any { host.endsWith(".$it") }
        require(protocol == "https" && trustedHost && url.userInfo == null &&
            (url.port == -1 || url.port == 443)) {
            "Недоверенный адрес загрузки обновления."
        }
        return url
    }
}
