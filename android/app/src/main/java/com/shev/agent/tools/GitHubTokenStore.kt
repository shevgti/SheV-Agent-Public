package com.shev.agent.tools

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts GitHub access and refresh tokens with a non-exportable Android Keystore key. */
class GitHubTokenStore(context: Context) : GitHubCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun save(accessToken: String, refreshToken: String?) {
        require(accessToken.isNotBlank()) { "GitHub access token must not be blank." }
        val access = encrypt(accessToken)
        val editor = preferences.edit()
            .putString(ACCESS_IV, access.first)
            .putString(ACCESS_CIPHERTEXT, access.second)
        if (!refreshToken.isNullOrBlank()) {
            val refresh = encrypt(refreshToken)
            editor.putString(REFRESH_IV, refresh.first)
                .putString(REFRESH_CIPHERTEXT, refresh.second)
        } else {
            editor.remove(REFRESH_IV).remove(REFRESH_CIPHERTEXT)
        }
        check(editor.commit()) { "Не удалось надёжно сохранить GitHub-токены." }
    }

    @Synchronized
    override fun read(): String? = decrypt(ACCESS_IV, ACCESS_CIPHERTEXT)

    @Synchronized
    override fun readRefreshToken(): String? = decrypt(REFRESH_IV, REFRESH_CIPHERTEXT)

    @Synchronized
    override fun clear() {
        check(
            preferences.edit()
                .remove(ACCESS_IV).remove(ACCESS_CIPHERTEXT)
                .remove(REFRESH_IV).remove(REFRESH_CIPHERTEXT)
                .commit()
        ) { "Не удалось удалить сохранённые GitHub-токены." }
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(ivKey: String, ciphertextKey: String): String? {
        val ivValue = preferences.getString(ivKey, null) ?: return null
        val encryptedValue = preferences.getString(ciphertextKey, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(ivValue, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encryptedValue, Base64.NO_WRAP)), Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // A Keystore key can become permanently invalidated (for example,
            // after a lock-screen/security change). Remove both ciphertext and
            // the unusable key so the next OAuth connection can create a fresh key.
            preferences.edit()
                .remove(ACCESS_IV).remove(ACCESS_CIPHERTEXT)
                .remove(REFRESH_IV).remove(REFRESH_CIPHERTEXT)
                .commit()
            runCatching {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
                    .deleteEntry(KEY_ALIAS)
            }
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "shev_agent_github_oauth_aes"
        const val PREFS_NAME = "shev_agent_github_auth"
        const val ACCESS_IV = "access_iv"
        const val ACCESS_CIPHERTEXT = "access_ciphertext"
        const val REFRESH_IV = "refresh_iv"
        const val REFRESH_CIPHERTEXT = "refresh_ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
