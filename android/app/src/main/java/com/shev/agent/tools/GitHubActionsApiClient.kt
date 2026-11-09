package com.shev.agent.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Read-only GitHub Actions client. Token is injected by caller, never persisted or logged. */
data class GitHubHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

fun interface GitHubHttpTransport {
    @Throws(IOException::class)
    fun get(url: String, token: String): GitHubHttpResponse
}

class UrlConnectionGitHubHttpTransport : GitHubHttpTransport {
    override fun get(url: String, token: String): GitHubHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // Never forward a bearer token through an HTTP redirect.
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "SheV-Agent")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields.filterKeys { it != null }
                .mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
            GitHubHttpResponse(status, body, headers)
        } finally {
            connection.disconnect()
        }
    }
}

sealed interface GitHubActionsVerificationResult {
    data class Verified(val repository: String, val workflowPath: String, val runId: Long, val headSha: String, val jobsCount: Int) : GitHubActionsVerificationResult
    data class Rejected(val reason: String) : GitHubActionsVerificationResult
    data class Unavailable(val reason: String, val httpStatus: Int? = null) : GitHubActionsVerificationResult
}

/**
 * Reads one workflow run and every page of its jobs. It never starts, retries,
 * cancels, or modifies a workflow. The caller owns token acquisition/storage.
 */
class GitHubActionsApiClient(
    private val transport: GitHubHttpTransport = UrlConnectionGitHubHttpTransport(),
    private val apiBaseUrl: String = "https://api.github.com"
) {
    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 100
    }

    fun verifyRun(
        expectedRepository: String,
        expectedWorkflowPath: String,
        expectedHeadSha: String,
        expectedRunId: Long,
        token: String
    ): GitHubActionsVerificationResult {
        if (token.isBlank()) return GitHubActionsVerificationResult.Unavailable("Не передан GitHub-токен.")
        val parts = expectedRepository.split("/")
        if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.-]+")) || it == "." || it == ".." } ||
            expectedWorkflowPath.isBlank() || !expectedHeadSha.matches(Regex("[0-9a-fA-F]{40}")) || expectedRunId <= 0
        ) return GitHubActionsVerificationResult.Rejected("Некорректные ожидаемые идентификаторы CI.")

        return try {
            verifyRunInternal(expectedRepository, parts[0], parts[1], expectedWorkflowPath, expectedHeadSha, expectedRunId, token)
        } catch (error: IOException) {
            GitHubActionsVerificationResult.Unavailable("Ошибка сети GitHub: ${error.javaClass.simpleName}.")
        } catch (error: Exception) {
            GitHubActionsVerificationResult.Unavailable("Не удалось безопасно проверить ответ GitHub: ${error.javaClass.simpleName}.")
        }
    }

    private fun verifyRunInternal(
        expectedRepository: String,
        owner: String,
        repo: String,
        expectedWorkflowPath: String,
        expectedHeadSha: String,
        expectedRunId: Long,
        token: String
    ): GitHubActionsVerificationResult {
        val root = apiBaseUrl.trimEnd('/')
        if (!isTrustedApiRoot(root)) {
            return GitHubActionsVerificationResult.Rejected("Разрешён только доверенный HTTPS API GitHub.")
        }
        val runUrl = "$root/repos/$owner/$repo/actions/runs/$expectedRunId"
        val runResponse = transport.get(runUrl, token)
        if (runResponse.statusCode !in 200..299) return httpFailure(runResponse, "Не удалось получить запуск GitHub Actions.")
        val runJson = parseObject(runResponse.body)
            ?: return GitHubActionsVerificationResult.Unavailable("GitHub вернул некорректный JSON запуска.")
        val run = GitHubActionsRunEvidence(
            repository = runJson.objectOrNull("repository")?.stringOrNull("full_name").orEmpty(),
            runId = runJson.longOrNull("id") ?: -1L,
            // GitHub may append @refs/heads/... to the workflow path in run metadata.
            workflowPath = runJson.stringOrNull("path")?.substringBefore("@refs/").orEmpty(),
            headSha = runJson.stringOrNull("head_sha").orEmpty(),
            status = runJson.stringOrNull("status").orEmpty(),
            conclusion = runJson.stringOrNull("conclusion").orEmpty()
        )

        val jobsPath = "/repos/$owner/$repo/actions/runs/$expectedRunId/jobs"
        val firstJobsUrl = "$root$jobsPath?per_page=$PAGE_SIZE&page=1"
        val jobs = mutableListOf<GitHubActionsJobEvidence>()
        val jobIds = mutableSetOf<Long>()
        var totalCount: Int? = null
        var nextUrl: String? = firstJobsUrl
        var pageNumber = 0

        while (nextUrl != null) {
            if (++pageNumber > MAX_PAGES) {
                return GitHubActionsVerificationResult.Unavailable("Превышен безопасный предел страниц jobs.")
            }
            if (!isTrustedApiUrl(nextUrl, root, jobsPath, pageNumber)) {
                return GitHubActionsVerificationResult.Unavailable("GitHub вернул небезопасную ссылку пагинации.")
            }
            val response = transport.get(nextUrl, token)
            if (response.statusCode !in 200..299) return httpFailure(response, "Не удалось получить список jobs.")
            val page = parseObject(response.body)
                ?: return GitHubActionsVerificationResult.Unavailable("GitHub вернул некорректный JSON jobs.")
            val count = page.intOrNull("total_count")
                ?: return GitHubActionsVerificationResult.Unavailable("В ответе jobs отсутствует total_count.")
            if (count < 0 || (totalCount != null && totalCount != count)) {
                return GitHubActionsVerificationResult.Unavailable("Общее число jobs изменилось во время пагинации.")
            }
            totalCount = count
            val pageJobs = page.arrayOrNull("jobs")
                ?: return GitHubActionsVerificationResult.Unavailable("В ответе GitHub отсутствует массив jobs.")
            for (entry in pageJobs) {
                if (!entry.isJsonObject) return GitHubActionsVerificationResult.Unavailable("Некорректная запись job.")
                val job = entry.asJsonObject
                val id = job.longOrNull("id")
                    ?: return GitHubActionsVerificationResult.Unavailable("У job отсутствует идентификатор.")
                if (!jobIds.add(id)) return GitHubActionsVerificationResult.Unavailable("GitHub вернул повторяющийся job.")
                jobs += GitHubActionsJobEvidence(
                    name = job.stringOrNull("name").orEmpty(),
                    conclusion = job.stringOrNull("conclusion").orEmpty()
                )
            }
            val linkHeader = response.headers.entries.firstOrNull { it.key.equals("link", true) }?.value
            nextUrl = linkHeader?.let(::nextLink)
            if (nextUrl == null && jobs.size != count) {
                return GitHubActionsVerificationResult.Unavailable("Получено jobs: ${jobs.size}, GitHub сообщает: $count; список неполный.")
            }
        }

        val expectedCount = totalCount ?: 0
        if (!GitHubActionsEvidenceVerifier.isVerifiedSuccess(
                expectedRepository, expectedWorkflowPath, expectedHeadSha, expectedRunId,
                run, jobs, expectedCount
            )
        ) return GitHubActionsVerificationResult.Rejected(
            "Запуск не прошёл проверку репозитория, workflow, SHA, статуса или всех jobs."
        )
        return GitHubActionsVerificationResult.Verified(expectedRepository, expectedWorkflowPath, expectedRunId, expectedHeadSha, jobs.size)
    }

    private fun httpFailure(response: GitHubHttpResponse, fallback: String): GitHubActionsVerificationResult.Unavailable {
        val remaining = response.headers.entries.firstOrNull { it.key.equals("x-ratelimit-remaining", true) }?.value
        val reason = when {
            response.statusCode == 401 -> "GitHub отклонил авторизацию (401)."
            response.statusCode == 403 && remaining == "0" -> "Достигнут лимит запросов GitHub API."
            response.statusCode == 403 -> "GitHub запретил чтение ресурса (403); проверьте права токена."
            response.statusCode == 404 -> "Репозиторий или запуск не найден либо токен не имеет доступа (404)."
            response.statusCode == 429 -> "GitHub ограничил частоту запросов (429)."
            else -> "$fallback HTTP ${response.statusCode}."
        }
        return GitHubActionsVerificationResult.Unavailable(reason, response.statusCode)
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: RuntimeException) { null }

    private fun isTrustedApiRoot(root: String): Boolean = try {
        val uri = URI(root)
        uri.scheme == "https" && uri.host.equals("api.github.com", true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.orEmpty().isEmpty()
    } catch (_: Exception) { false }

    private fun isTrustedApiUrl(
        candidate: String,
        root: String,
        expectedPath: String,
        expectedPage: Int
    ): Boolean = try {
        val base = URI(root)
        val uri = URI(candidate)
        uri.scheme == "https" && uri.host.equals(base.host, true) &&
            uri.port == base.port && uri.userInfo == null && uri.rawFragment == null &&
            uri.rawPath == expectedPath &&
            uri.rawQuery == "per_page=$PAGE_SIZE&page=$expectedPage"
    } catch (_: Exception) { false }

    private fun nextLink(header: String): String? =
        Regex("<([^>]+)>\\s*;\\s*rel=\"next\"").find(header)?.groupValues?.getOrNull(1)

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.longOrNull(name: String): Long? = try {
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong
    } catch (_: RuntimeException) { null }

    private fun JsonObject.intOrNull(name: String): Int? = try {
        get(name)?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (_: RuntimeException) { null }

    private fun JsonObject.objectOrNull(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arrayOrNull(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
}
