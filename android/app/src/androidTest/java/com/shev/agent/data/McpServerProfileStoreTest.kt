package com.shev.agent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shev.agent.core.CuratedMcpProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpServerProfileStoreTest {
    private lateinit var context: Context
    private lateinit var store: McpServerProfileStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(McpServerProfileStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = McpServerProfileStore(context)
    }

    @Test fun curatedProfilesAreVisibleAndDisabledByDefault() {
        val context7 = store.getProfile("context7")
        assertNotNull(context7)
        assertFalse(context7!!.enabled)
        assertTrue(context7.approvedToolNames.isEmpty())
    }

    @Test fun profileMutationRequiresExplicitApproval() {
        val profile = CuratedMcpProfiles.entries.first { it.id == "context7" }
            .copy(approvedToolNames = setOf("resolve-library-id"))
        assertFalse(store.saveProfile(profile, explicitlyApprovedByUser = false))
        assertNull(store.getProfile("context7")?.takeIf { it.approvedToolNames.isNotEmpty() })
    }

    @Test fun enablingRequiresAnExplicitlyApprovedToolAllowlist() {
        assertFalse(store.setEnabled("context7", true, explicitlyApprovedByUser = true))
        assertTrue(store.setApprovedTools(
            "context7", setOf("resolve-library-id"), explicitlyApprovedByUser = true
        ))
        assertTrue(store.setEnabled("context7", true, explicitlyApprovedByUser = true))
        assertTrue(store.getProfile("context7")!!.enabled)
    }

    @Test fun allowlistChangeDisablesProfileUntilReconfirmed() {
        store.setApprovedTools("context7", setOf("resolve-library-id"), true)
        store.setEnabled("context7", true, true)
        assertTrue(store.setApprovedTools("context7", setOf("query-docs"), true))
        val profile = store.getProfile("context7")!!
        assertFalse(profile.enabled)
        assertEquals(setOf("query-docs"), profile.approvedToolNames)
    }

    @Test fun profileMetadataPersistsAcrossStoreInstances() {
        store.setApprovedTools("context7", setOf("resolve-library-id"), true)
        val reloaded = McpServerProfileStore(context).getProfile("context7")
        assertEquals(setOf("resolve-library-id"), reloaded?.approvedToolNames)
        assertFalse(reloaded!!.enabled)
    }
}
