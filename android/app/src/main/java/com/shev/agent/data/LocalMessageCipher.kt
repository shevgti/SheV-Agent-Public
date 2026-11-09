package com.shev.agent.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts local transcript content with an AES-256-GCM key held by Android Keystore.
 * Plaintext is never written to the Room transcript table.
 */
object LocalMessageCipher {
    private const val KEY_ALIAS = "shev-agent-conversation-v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private val associatedData = "SheVAgent:conversation:v1".toByteArray(StandardCharsets.UTF_8)

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val packed = ByteBuffer.allocate(cipher.iv.size + encrypted.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(storedValue: String): String {
        require(storedValue.startsWith(PREFIX)) { "Conversation record is not encrypted with the supported format." }
        val packed = Base64.decode(storedValue.removePrefix(PREFIX), Base64.NO_WRAP)
        require(packed.size > IV_LENGTH_BYTES) { "Encrypted conversation record is truncated." }
        val iv = packed.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = packed.copyOfRange(IV_LENGTH_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(associatedData)
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
