package com.shev.agent.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreTest {
    private lateinit var context: Context
    private lateinit var store: AndroidKeystoreSecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AndroidKeystoreSecretStore(context)
        context.getSharedPreferences(AndroidKeystoreSecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun encryptsAndDecryptsSecretAcrossStoreInstances() {
        val secret = "test-token-never-log-this"
        store.putSecret("mcp.context7", secret)
        assertEquals(secret, AndroidKeystoreSecretStore(context).getSecret("mcp.context7"))
        val stored = context.getSharedPreferences(
            AndroidKeystoreSecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE
        ).getString("mcp.context7", null)
        assertFalse(stored.orEmpty().contains(secret))
    }

    @Test fun authenticatedSecretIdPreventsCiphertextSwapping() {
        store.putSecret("mcp.context7", "context7-token")
        val preferences = context.getSharedPreferences(
            AndroidKeystoreSecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE
        )
        val ciphertext = preferences.getString("mcp.context7", null)!!
        preferences.edit().putString("mcp.github", ciphertext).commit()
        assertNull(store.getSecret("mcp.github"))
    }

    @Test fun tamperedCiphertextIsRejected() {
        store.putSecret("mcp.context7", "context7-token")
        val preferences = context.getSharedPreferences(
            AndroidKeystoreSecretStore.PREFERENCES_NAME, Context.MODE_PRIVATE
        )
        preferences.edit().putString("mcp.context7", "AQIDBA==").commit()
        assertNull(store.getSecret("mcp.context7"))
    }

    @Test fun removeSecretDeletesStoredCiphertext() {
        store.putSecret("mcp.context7", "context7-token")
        assertTrue(store.removeSecret("mcp.context7"))
        assertFalse(store.containsSecret("mcp.context7"))
        assertNull(store.getSecret("mcp.context7"))
    }
}
