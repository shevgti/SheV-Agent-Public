package com.shev.agent.security

import com.shev.agent.core.McpAuthenticationKind
import com.shev.agent.core.McpServerProfile

fun interface McpCredentialSource {
    fun get(profile: McpServerProfile): String?
}

/**
 * Supplies a stored credential only for an enabled, authenticated server profile.
 * Tokens remain outside model context and are retrieved from Android Keystore storage.
 */
class KeystoreMcpCredentialProvider(
    private val secrets: AndroidKeystoreSecretStore
) : McpCredentialSource {
    @Synchronized
    override fun get(profile: McpServerProfile): String? {
        if (!profile.enabled || profile.authentication == McpAuthenticationKind.NONE) return null
        return secrets.getSecret(secretId(profile))
    }

    @Synchronized
    fun store(profile: McpServerProfile, credential: String) {
        require(profile.enabled)
        require(profile.authentication != McpAuthenticationKind.NONE)
        secrets.putSecret(secretId(profile), credential)
    }

    @Synchronized
    fun remove(profile: McpServerProfile): Boolean = secrets.removeSecret(secretId(profile))

    private fun secretId(profile: McpServerProfile): String = "mcp.${profile.id}"
}
