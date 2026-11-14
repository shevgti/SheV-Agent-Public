package com.shev.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Read-only public web search adapter. It contacts one fixed search endpoint;
 * callers cannot supply URLs, headers, hosts or request methods.
 */
class PublicWebSearchTool(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 12_000
) {
    init {
        require(connectTimeoutMillis in 1_000..20_000)
        require(readTimeoutMillis in 1_000..30_000)
    }

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        require(normalized.length in 2..200) { "Запрос должен содержать от 2 до 200 символов." }
        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        val connection = (URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html")
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Поиск недоступен (HTTP $status).")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(MAX_RESPONSE_CHARS) }
            val results = RESULT_LINK.findAll(html).mapNotNull { match ->
                val tag = match.groupValues[1]
                val title = match.groupValues[2].let(::decodeHtml).trim()
                val rawHref = HREF_ATTRIBUTE.find(tag)?.groupValues?.getOrNull(1)?.let(::decodeHtml)?.trim()
                val href = rawHref?.let(::unwrapSearchRedirect)?.let(::safeResultUrl)
                if (title.isBlank() || href == null) null else Triple(title, href, "")
            }.distinctBy { it.second }.take(MAX_RESULTS).toList()

            if (results.isEmpty()) {
                "Поиск выполнен, но выдача не распознана. Попробуй переформулировать запрос."
            } else buildString {
                append("Результаты публичного веб-поиска по запросу: ").append(normalized).append("\n")
                results.forEachIndexed { index, item ->
                    append(index + 1).append(". ").append(item.first).append("\n")
                    append(item.second).append("\n")
                    if (item.third.isNotBlank()) append(item.third).append("\n")
                    append("\n")
                }
                append("Это поисковые сниппеты, а не полная проверка содержимого страниц.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unwrapSearchRedirect(value: String): String {
        val uri = runCatching { java.net.URI(if (value.startsWith("//")) "https:$value" else value) }.getOrNull()
            ?: return value
        if (uri.host == "duckduckgo.com" && uri.path == "/l/") {
            val target = uri.rawQuery.orEmpty().split("&").firstOrNull { it.startsWith("uddg=") }
                ?.substringAfter("uddg=")
            if (!target.isNullOrBlank()) return runCatching {
                java.net.URLDecoder.decode(target, Charsets.UTF_8.name())
            }.getOrDefault(value)
        }
        return value
    }

    private fun safeResultUrl(value: String): String? {
        val candidate = when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("//") -> "https:$value"
            else -> return null
        }
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null) return null
        return candidate.takeIf { it.length <= 2_048 }
    }

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&#x27;", "'", ignoreCase = true)
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\s+"), " ")

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36"
        private const val MAX_RESPONSE_CHARS = 500_000
        private const val MAX_RESULTS = 5
        private val RESULT_LINK = Regex("(<a(?=[^>]*class=[\\\"'][^\\\"']*result__a)(?=[^>]*href=)[^>]*>)(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val HREF_ATTRIBUTE = Regex("href=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
    }
}
