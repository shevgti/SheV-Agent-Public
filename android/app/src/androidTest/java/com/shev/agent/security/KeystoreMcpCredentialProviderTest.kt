package com.shev.agent.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shev.agent.core.CuratedMcpProfiles
import com.shev.agent.core.McpAuthenticationKind
import com.shev.agent.core.McpServerProfile
import com.shev.agent.core.McpServerTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreMcpCredentialProviderTest {
    private lateinit var provider: KeystoreMcpCredentialProvider

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidKeystoreSecretStore(context)
        context.getSharedPreferences(AndroidKeystoreSecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        provider = KeystoreMcpCredentialProvider(store)
    }

    @Test fun returnsCredentialOnlyForEnabledAuthenticatedProfile() {
        val enabled = CuratedMcpProfiles.entries.first { it.id == "context7" }.copy(enabled = true)
        provider.store(enabled, "context7-test-token")
        assertEquals("context7-test-token", provider.get(enabled))
        assertNull(provider.get(enabled.copy(enabled = false)))
    }

    @Test fun profileWithoutAuthenticationNeverReceivesStoredCredential() {
        val profile = McpServerProfile(
            id = "public-docs",
            displayName = "Public docs",
            endpoint = "https://example.org/mcp",
            authentication = McpAuthenticationKind.NONE,
            trust = McpServerTrust.USER_HOSTED,
            enabled = true
        )
        assertNull(provider.get(profile))
    }
}
