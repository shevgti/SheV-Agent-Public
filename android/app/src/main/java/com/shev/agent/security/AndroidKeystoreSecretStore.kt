package com.shev.agent.security

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Small encrypted secret store for OAuth/bearer credentials.
 * Only ciphertext is written to preferences; the AES key remains in Android Keystore.
 * Secret IDs are authenticated as GCM AAD to prevent ciphertext swapping between profiles.
 */
class AndroidKeystoreSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun putSecret(secretId: String, secret: String) {
        validate(secretId, secret)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(secretId.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        val envelope = byteArrayOf(FORMAT_VERSION) + cipher.iv + ciphertext
        check(preferences.edit().putString(secretId, Base64.encodeToString(envelope, Base64.NO_WRAP)).commit()) {
            "Не удалось сохранить зашифрованный секрет"
        }
    }

    @Synchronized
    fun getSecret(secretId: String): String? {
        validateId(secretId)
        val encoded = preferences.getString(secretId, null) ?: return null
        return runCatching {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            require(envelope.size >= 1 + GCM_IV_BYTES + GCM_TAG_BYTES)
            require(envelope[0] == FORMAT_VERSION)
            val iv = envelope.copyOfRange(1, 1 + GCM_IV_BYTES)
            val ciphertext = envelope.copyOfRange(1 + GCM_IV_BYTES, envelope.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(secretId.toByteArray(StandardCharsets.UTF_8))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun removeSecret(secretId: String): Boolean {
        validateId(secretId)
        return preferences.edit().remove(secretId).commit()
    }

    fun containsSecret(secretId: String): Boolean {
        validateId(secretId)
        return preferences.contains(secretId)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun validate(secretId: String, secret: String) {
        validateId(secretId)
        require(secret.isNotBlank() && secret.length <= MAX_SECRET_CHARS)
    }

    private fun validateId(secretId: String) {
        require(secretId.matches(Regex("[a-z][a-z0-9_.:-]{0,127}")))
    }

    companion object {
        const val PREFERENCES_NAME = "shev_agent_keystore_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "shev.agent.secrets.aes.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val MAX_SECRET_CHARS = 16_384
    }
}
