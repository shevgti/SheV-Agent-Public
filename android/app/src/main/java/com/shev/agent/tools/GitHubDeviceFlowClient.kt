package com.shev.agent.tools

import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long
)

sealed interface GitHubDevicePollResult {
    data class Authorized(val accessToken: String, val tokenType: String, val scope: String, val refreshToken: String?, val expiresInSeconds: Long?) : GitHubDevicePollResult
    data object Pending : GitHubDevicePollResult
    data class SlowDown(val recommendedIntervalSeconds: Long) : GitHubDevicePollResult
    data object Expired : GitHubDevicePollResult
    data object Denied : GitHubDevicePollResult
    data class Failed(val reason: String, val httpStatus: Int? = null) : GitHubDevicePollResult
}

data class GitHubOAuthHttpResponse(val statusCode: Int, val body: String)

data class GitHubRefreshedTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresInSeconds: Long?,
    val refreshTokenExpiresInSeconds: Long?
)

sealed interface GitHubTokenRefreshResult {
    data class Refreshed(val tokens: GitHubRefreshedTokens) : GitHubTokenRefreshResult
    data class Failed(val reason: String, val httpStatus: Int? = null) : GitHubTokenRefreshResult
}

fun interface GitHubOAuthTransport {
    @Throws(IOException::class)
    fun postForm(url: String, parameters: Map<String, String>): GitHubOAuthHttpResponse
}

/** GitHub Device Flow transport. It never opens a browser or persists/logs secrets. */
class GitHubDeviceFlowClient(
    private val transport: GitHubOAuthTransport = UrlConnectionGitHubOAuthTransport(),
    private val githubBaseUrl: String = "https://github.com"
) : GitHubOAuthRefresher {
    fun requestDeviceCode(clientId: String): Result<GitHubDeviceCode> {
        if (!isValidClientId(clientId)) return Result.failure(IllegalArgumentException("Некорректный Client ID GitHub App."))
        return runCatching {
            val response = transport.postForm(
                "${githubBaseUrl.trimEnd('/')}/login/device/code",
                mapOf("client_id" to clientId, "scope" to "offline_access")
            )
            val json = parse(response.body)
            if (response.statusCode !in 200..299) {
                throw IOException("GitHub Device Flow вернул HTTP ${response.statusCode}.")
            }
            val code = json.string("device_code")
            val userCode = json.string("user_code")
            val verificationUri = json.string("verification_uri")
            val expires = json.long("expires_in")
            val interval = json.long("interval")?.coerceAtLeast(5) ?: 5L
            require(!code.isNullOrBlank() && !userCode.isNullOrBlank()) { "GitHub не вернул код устройства." }
            require(isTrustedVerificationUri(verificationUri)) { "GitHub вернул недоверенный адрес подтверждения." }
            require(expires != null && expires > 0) { "GitHub вернул некорректный срок действия кода." }
            GitHubDeviceCode(code, userCode, verificationUri!!, expires, interval)
        }
    }

    fun pollForAccessToken(clientId: String, deviceCode: String): GitHubDevicePollResult {
        if (!isValidClientId(clientId) || deviceCode.isBlank()) {
            return GitHubDevicePollResult.Failed("Не заданы параметры авторизации.")
        }
        return try {
            val response = transport.postForm(
                "${githubBaseUrl.trimEnd('/')}/login/oauth/access_token",
                mapOf("client_id" to clientId, "device_code" to deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")
            )
            val json = parse(response.body)
            if (response.statusCode !in 200..299) {
                return GitHubDevicePollResult.Failed("GitHub вернул HTTP ${response.statusCode}.", response.statusCode)
            }
            val token = json.string("access_token")
            if (!token.isNullOrBlank()) {
                return GitHubDevicePollResult.Authorized(
                    token,
                    json.string("token_type").orEmpty(),
                    json.string("scope").orEmpty(),
                    json.string("refresh_token"),
                    json.long("expires_in")
                )
            }
            when (json.string("error")) {
                "authorization_pending" -> GitHubDevicePollResult.Pending
                "slow_down" -> GitHubDevicePollResult.SlowDown((json.long("interval") ?: 5L).coerceAtLeast(5))
                "expired_token", "token_expired" -> GitHubDevicePollResult.Expired
                "access_denied" -> GitHubDevicePollResult.Denied
                else -> GitHubDevicePollResult.Failed("GitHub не завершил авторизацию: ${json.string("error") ?: "неизвестная ошибка"}.")
            }
        } catch (_: IOException) {
            GitHubDevicePollResult.Failed("Не удалось связаться с GitHub.")
        } catch (_: RuntimeException) {
            GitHubDevicePollResult.Failed("GitHub вернул некорректный ответ авторизации.")
        }
    }

    /** Exchanges a GitHub App refresh token and requires the rotated refresh token. */
    override fun refreshAccessToken(clientId: String, refreshToken: String): GitHubTokenRefreshResult {
        if (!isValidClientId(clientId) || refreshToken.isBlank()) {
            return GitHubTokenRefreshResult.Failed("Не заданы параметры обновления GitHub-токена.")
        }
        return try {
            val response = transport.postForm(
                "${githubBaseUrl.trimEnd('/')}/login/oauth/access_token",
                mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken
                )
            )
            val json = parse(response.body)
            if (response.statusCode !in 200..299) {
                return GitHubTokenRefreshResult.Failed("GitHub вернул HTTP ${response.statusCode} при обновлении токена.", response.statusCode)
            }
            val access = json.string("access_token")
            val rotatedRefresh = json.string("refresh_token")
            if (access.isNullOrBlank() || rotatedRefresh.isNullOrBlank()) {
                return GitHubTokenRefreshResult.Failed(
                    "GitHub не вернул новую пару токенов. Старые данные сохранены; требуется повторное подключение."
                )
            }
            GitHubTokenRefreshResult.Refreshed(
                GitHubRefreshedTokens(access, rotatedRefresh, json.long("expires_in"), json.long("refresh_token_expires_in"))
            )
        } catch (_: IOException) {
            GitHubTokenRefreshResult.Failed("Не удалось связаться с GitHub для обновления токена.")
        } catch (_: RuntimeException) {
            GitHubTokenRefreshResult.Failed("GitHub вернул некорректный ответ обновления токена.")
        }
    }

    private fun parse(body: String) = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("Invalid JSON")
    } catch (_: RuntimeException) {
        throw IOException("Некорректный JSON от GitHub.")
    }

    private fun isTrustedVerificationUri(value: String?): Boolean = try {
        val uri = java.net.URI(value)
        uri.scheme == "https" && uri.host.equals("github.com", true) &&
            (uri.path == "/login/device" || uri.path == "/login/device/")
    } catch (_: Exception) { false }

    private fun isValidClientId(value: String) =
        value.matches(Regex("[A-Za-z0-9._-]{8,100}"))

    private fun escapeJson(value: String) = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun com.google.gson.JsonObject.string(name: String) =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun com.google.gson.JsonObject.long(name: String) =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
}

class UrlConnectionGitHubOAuthTransport : GitHubOAuthTransport {
    override fun postForm(url: String, parameters: Map<String, String>): GitHubOAuthHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("User-Agent", "SheV-Agent")
        }
        return try {
            val formBody = parameters.entries.joinToString("&") { (key, value) ->
                java.net.URLEncoder.encode(key, "UTF-8") + "=" + java.net.URLEncoder.encode(value, "UTF-8")
            }
            connection.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            GitHubOAuthHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}
