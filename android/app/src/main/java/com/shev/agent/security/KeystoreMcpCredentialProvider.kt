package com.shev.agent.security

import com.shev.agent.core.McpAuthenticationKind
import com.shev.agent.core.McpServerProfile

/**
 * Supplies a stored credential only for an enabled, authenticated server profile.
 * Pass this method as RemoteMcpClient's credentialProvider; tokens remain outside model context.
 */
fun interface McpCredentialSource {
    fun get(profile: McpServerProfile): String?
}

class KeystoreMcpCredentialProvider(
    private val secrets: AndroidKeystoreSecretStore
) : McpCredentialSource {
    override fun get(profile: McpServerProfile): String? {
        if (!profile.enabled || profile.authentication == McpAuthenticationKind.NONE) return null
        return secrets.getSecret(secretId(profile))
    }

    fun store(profile: McpServerProfile, credential: String) {
        require(profile.enabled)
        require(profile.authentication != McpAuthenticationKind.NONE)
        secrets.putSecret(secretId(profile), credential)
    }

    fun remove(profile: McpServerProfile): Boolean = secrets.removeSecret(secretId(profile))

    private fun secretId(profile: McpServerProfile): String = "mcp.${profile.id}"
}
