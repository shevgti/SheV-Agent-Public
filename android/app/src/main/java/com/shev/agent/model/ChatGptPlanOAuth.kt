package com.shev.agent.model

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64 as AndroidBase64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Official Sign in with ChatGPT flow for an open-source, locally running client.
 * No API key or client secret is used. OAuth tokens stay encrypted on this device.
 */
internal class ChatGptPlanOAuth(
    private val context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences("shev_openai", Context.MODE_PRIVATE)
) {
    suspend fun authorize(): ChatGptPlanSession = withContext(Dispatchers.IO) {
        val state = randomUrlSafe(32)
        val nonce = randomUrlSafe(32)
        val verifier = randomUrlSafe(32)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val hostId = preferences.getString(KEY_HOST_ID, null) ?: "urn:uuid:${UUID.randomUUID()}".also {
            preferences.edit().putString(KEY_HOST_ID, it).commit()
        }

        ServerSocket().use { server ->
            server.reuseAddress = false
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            server.soTimeout = CALLBACK_TIMEOUT_MILLIS
            val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
            val authUri = Uri.Builder()
                .scheme("https")
                .authority("auth.openai.com")
                .appendPath("api")
                .appendPath("accounts")
                .appendPath("authorize")
                .appendQueryParameter("client_id", DYNAMIC_CLIENT_ID)
                .appendQueryParameter("agent_name_hint", APP_NAME)
                .appendQueryParameter("ext_agent_host_id", hostId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", REQUIRED_SCOPES)
                .appendQueryParameter("resource", RESOURCE)
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .build()

            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, authUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            val callback = withTimeout(CALLBACK_TIMEOUT_MILLIS.toLong()) {
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    val requestLine = reader.readLine().orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) { /* consume headers */ }
                    val target = requestLine.split(' ').getOrNull(1)
                        ?: throw IllegalStateException("Некорректный ответ OAuth callback.")
                    val callbackUri = Uri.parse("http://127.0.0.1$target")
                    val body = "<!doctype html><meta charset=utf-8><title>SheV Agent</title><p>Авторизация завершена. Вернитесь в SheV Agent.</p>"
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(response.toByteArray(StandardCharsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                    require(callbackUri.path == "/auth/callback") { "Неверный OAuth callback." }
                    require(callbackUri.getQueryParameter("state") == state) { "Проверка OAuth state не пройдена." }
                    val oauthError = callbackUri.getQueryParameter("error")
                    require(oauthError.isNullOrBlank()) {
                        if (oauthError == "access_denied") "Вход или разрешение на использование плана ChatGPT отменено."
                        else "Ошибка авторизации ChatGPT: ${oauthError ?: "unknown"}."
                    }
                    val code = callbackUri.getQueryParameter("code")
                        ?: throw IllegalStateException("OpenAI не вернул код авторизации.")
                    val issuedClientId = callbackUri.getQueryParameter("client_id")
                        ?: throw IllegalStateException("OpenAI не вернул зарегистрированный client_id.")
                    require(issuedClientId.startsWith("oaiapp_") && issuedClientId.length <= 256) {
                        "OpenAI вернул некорректный идентификатор клиента."
                    }
                    code to issuedClientId
                }
            }

            val tokenResponse = postForm(
                TOKEN_ENDPOINT,
                linkedMapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to callback.second,
                    "code" to callback.first,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirectUri,
                    "resource" to RESOURCE
                )
            )
            val scopes = tokenResponse.get("scope")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                .split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
            require(REQUIRED_PLAN_SCOPE in scopes) {
                "ChatGPT вошёл, но разрешение на использование плана ChatGPT не выдано. Повторите подключение и разрешите использование плана."
            }
            val idToken = tokenResponse.get("id_token")?.asString
                ?: throw IllegalStateException("OpenAI не вернул ID token.")
            val identity = validateIdToken(idToken, callback.second, nonce)
            val accessToken = tokenResponse.get("access_token")?.asString
                ?: throw IllegalStateException("OpenAI не вернул access token.")
            val refreshToken = tokenResponse.get("refresh_token")?.asString
                ?: throw IllegalStateException("OpenAI не вернул refresh token.")
            val expiresIn = tokenResponse.get("expires_in")?.asLong ?: 3600L
            val session = ChatGptPlanSession(
                clientId = callback.second,
                hostId = hostId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                idToken = idToken,
                subject = identity.first,
                email = identity.second,
                scopes = scopes,
                expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + expiresIn
            )
            save(session)
            session
        }
    }

    suspend fun validAccessToken(): String = withContext(Dispatchers.IO) {
        var session = load() ?: throw IllegalStateException("Сначала подключи аккаунт ChatGPT.")
        if (session.expiresAtEpochSeconds > System.currentTimeMillis() / 1000L + REFRESH_SKEW_SECONDS) {
            return@withContext session.accessToken
        }
        val response = postForm(
            TOKEN_ENDPOINT,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to session.clientId,
                "refresh_token" to session.refreshToken,
                "resource" to RESOURCE
            )
        )
        val newScopes = response.get("scope")?.takeIf { it.isJsonPrimitive }?.asString
            ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet() ?: session.scopes
        require(REQUIRED_PLAN_SCOPE in newScopes) { "Разрешение ChatGPT plan usage больше не действует. Подключи ChatGPT заново." }
        session = session.copy(
            accessToken = response.get("access_token")?.asString
                ?: throw IllegalStateException("Не удалось обновить ChatGPT access token."),
            refreshToken = response.get("refresh_token")?.asString
                ?: throw IllegalStateException("OpenAI не вернул новый refresh token."),
            scopes = newScopes,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + (response.get("expires_in")?.asLong ?: 3600L)
        )
        save(session)
        session.accessToken
    }

    fun load(): ChatGptPlanSession? {
        val encrypted = preferences.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            com.google.gson.Gson().fromJson(PlanSessionCipher.decrypt(encrypted), ChatGptPlanSession::class.java)
        }.getOrElse { throw IllegalStateException("Сохранённая авторизация ChatGPT недоступна. Подключи аккаунт заново.") }
    }

    fun disconnect() {
        preferences.edit().remove(KEY_SESSION).apply()
    }

    private fun save(session: ChatGptPlanSession) {
        preferences.edit().putString(KEY_SESSION, PlanSessionCipher.encrypt(com.google.gson.Gson().toJson(session))).commit()
    }

    private fun validateIdToken(token: String, clientId: String, expectedNonce: String): Pair<String, String> {
        val pieces = token.split('.')
        require(pieces.size == 3) { "Некорректный ID token." }
        val header = JsonParser.parseString(decodePart(pieces[0])).asJsonObject
        val claims = JsonParser.parseString(decodePart(pieces[1])).asJsonObject
        require(header.get("alg")?.asString == "RS256") { "Неподдерживаемый алгоритм ID token." }
        val kid = header.get("kid")?.asString ?: throw IllegalStateException("В ID token отсутствует ключ подписи.")
        val discovery = getJson(DISCOVERY_URL)
        val issuer = discovery.get("issuer")?.asString ?: "https://auth.openai.com"
        val jwksUri = discovery.get("jwks_uri")?.asString
            ?: throw IllegalStateException("Не удалось получить OpenAI JWKS endpoint.")
        val keys = getJson(jwksUri).getAsJsonArray("keys")
        val jwk = keys.firstOrNull { it.asJsonObject.get("kid")?.asString == kid }?.asJsonObject
            ?: throw IllegalStateException("Ключ подписи OpenAI не найден.")
        require(jwk.get("kty")?.asString == "RSA" && jwk.get("use")?.asString in listOf(null, "sig")) {
            "Некорректный ключ подписи OpenAI."
        }
        val modulus = BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").asString))
        val exponent = BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").asString))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${pieces[0]}.${pieces[1]}".toByteArray(StandardCharsets.US_ASCII))
        require(signature.verify(Base64.getUrlDecoder().decode(pieces[2]))) { "Подпись ID token OpenAI недействительна." }
        require(claims.get("iss")?.asString == issuer) { "Неверный издатель ID token." }
        val audience = claims.get("aud")
        val audienceMatches = if (audience?.isJsonArray == true) {
            audience.asJsonArray.any { it.asString == clientId }
        } else audience?.isJsonPrimitive == true && audience.asString == clientId
        require(audienceMatches) { "ID token выдан для другого клиента." }
        require(claims.get("nonce")?.asString == expectedNonce) { "Проверка OAuth nonce не пройдена." }
        require((claims.get("exp")?.asLong ?: 0L) > System.currentTimeMillis() / 1000L) { "ID token истёк." }
        val subject = claims.get("sub")?.asString?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("В ID token отсутствует subject.")
        return subject to claims.get("email")?.asString.orEmpty()
    }

    private fun postForm(url: String, fields: Map<String, String>): JsonObject {
        val body = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return requestJson(url, "application/x-www-form-urlencoded", body)
    }

    private fun getJson(url: String): JsonObject = requestJson(url, null, null)

    private fun requestJson(url: String, contentType: String?, body: String?): JsonObject {
        val connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) doOutput = true
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(MAX_HTTP_BODY_CHARS) }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    JsonParser.parseString(response).asJsonObject.let { json ->
                        json.get("error_description")?.asString ?: json.get("error")?.asString
                    }
                }.getOrNull()
                throw IllegalStateException("Ошибка авторизации ChatGPT (HTTP $status)${detail?.let { ": $it" } ?: "."}")
            }
            return JsonParser.parseString(response).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun decodePart(part: String): String = String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8)

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val APP_NAME = "SheV Agent"
        private const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        private const val REQUIRED_PLAN_SCOPE = "chatgpt.tokens.use.direct"
        private const val REQUIRED_SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
        private const val DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration"
        private const val CALLBACK_TIMEOUT_MILLIS = 180_000
        private const val REFRESH_SKEW_SECONDS = 300L
        private const val MAX_HTTP_BODY_CHARS = 128_000
        private const val KEY_HOST_ID = "chatgpt_plan_host_id"
        private const val KEY_SESSION = "chatgpt_plan_session"
    }
}

internal data class ChatGptPlanSession(
    val clientId: String,
    val hostId: String,
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val subject: String,
    val email: String,
    val scopes: Set<String>,
    val expiresAtEpochSeconds: Long
)

/** Dedicated Keystore encryption for the full OAuth session, including the rotating refresh token. */
private object PlanSessionCipher {
    private const val KEY_ALIAS = "shev-chatgpt-plan-session-v1"
    private const val PREFIX = "chatgpt-plan:v1:"
    private val aad = "SheVAgent:chatgpt-plan-session:v1".toByteArray(StandardCharsets.UTF_8)

    fun encrypt(value: String): String {
        require(value.isNotBlank() && value.length <= 32_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + AndroidBase64.encodeToString(cipher.iv + encrypted, AndroidBase64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        require(value.startsWith(PREFIX))
        val packed = AndroidBase64.decode(value.removePrefix(PREFIX), AndroidBase64.NO_WRAP)
        require(packed.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
