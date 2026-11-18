package com.shev.agent.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.shev.agent.core.CuratedMcpProfiles
import com.shev.agent.core.McpAuthenticationKind
import com.shev.agent.core.McpServerProfile
import com.shev.agent.core.McpServerTrust
import com.shev.agent.core.McpTransportKind

fun interface McpProfileSource {
    fun getProfile(profileId: String): McpServerProfile?
}

/**
 * Persists MCP endpoint metadata only. Credentials are stored separately in Android Keystore.
 * Every mutation requires an explicit user-approval signal from the settings/controller layer.
 */
class McpServerProfileStore(context: Context) : McpProfileSource {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun listProfiles(): List<McpServerProfile> {
        val saved = readSavedProfiles() ?: return CuratedMcpProfiles.entries
        return (CuratedMcpProfiles.entries.associateBy { it.id } + saved).values.toList()
    }

    @Synchronized
    override fun getProfile(profileId: String): McpServerProfile? =
        listProfiles().firstOrNull { it.id == profileId }

    @Synchronized
    fun saveProfile(profile: McpServerProfile, explicitlyApprovedByUser: Boolean): Boolean {
        if (!explicitlyApprovedByUser) return false
        if (profile.enabled && profile.approvedToolNames.isEmpty()) return false
        val saved = readSavedProfiles() ?: return false
        saved[profile.id] = profile
        return writeSavedProfiles(saved)
    }

    @Synchronized
    fun setEnabled(profileId: String, enabled: Boolean, explicitlyApprovedByUser: Boolean): Boolean {
        if (!explicitlyApprovedByUser) return false
        val profile = getProfile(profileId) ?: return false
        if (enabled && profile.approvedToolNames.isEmpty()) return false
        return saveProfile(profile.copy(enabled = enabled), explicitlyApprovedByUser = true)
    }

    @Synchronized
    fun setApprovedTools(
        profileId: String,
        toolNames: Set<String>,
        explicitlyApprovedByUser: Boolean
    ): Boolean {
        if (!explicitlyApprovedByUser) return false
        val profile = getProfile(profileId) ?: return false
        val updated = runCatching { profile.copy(approvedToolNames = toolNames, enabled = false) }.getOrNull()
            ?: return false
        return saveProfile(updated, explicitlyApprovedByUser = true)
    }

    private fun readSavedProfiles(): MutableMap<String, McpServerProfile>? {
        val raw = preferences.getString(KEY_PROFILES, null) ?: return linkedMapOf()
        return runCatching {
            val root = JSONObject(raw)
            require(root.getInt("version") == FORMAT_VERSION)
            val array = root.getJSONArray("profiles")
            val result = linkedMapOf<String, McpServerProfile>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val profile = McpServerProfile(
                    id = id,
                    displayName = item.getString("displayName"),
                    endpoint = item.getString("endpoint"),
                    transport = McpTransportKind.valueOf(item.getString("transport")),
                    authentication = McpAuthenticationKind.valueOf(item.getString("authentication")),
                    trust = McpServerTrust.valueOf(item.getString("trust")),
                    enabled = item.optBoolean("enabled", false),
                    approvedToolNames = item.getJSONArray("approvedToolNames").toStringSet(),
                    notes = item.optString("notes")
                )
                require(!profile.enabled || profile.approvedToolNames.isNotEmpty())
                require(result.put(id, profile) == null)
            }
            result
        }.getOrNull()
    }

    private fun writeSavedProfiles(profiles: Map<String, McpServerProfile>): Boolean {
        if (profiles.size > MAX_PROFILES) return false
        val array = JSONArray()
        profiles.values.sortedBy { it.id }.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("displayName", profile.displayName)
                    .put("endpoint", profile.endpoint)
                    .put("transport", profile.transport.name)
                    .put("authentication", profile.authentication.name)
                    .put("trust", profile.trust.name)
                    .put("enabled", profile.enabled)
                    .put("approvedToolNames", JSONArray(profile.approvedToolNames.sorted()))
                    .put("notes", profile.notes)
            )
        }
        return preferences.edit()
            .putString(KEY_PROFILES, JSONObject().put("version", FORMAT_VERSION).put("profiles", array).toString())
            .commit()
    }

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).map { getString(it) }.toSet()

    companion object {
        const val PREFERENCES_NAME = "shev_agent_mcp_profiles"
        private const val KEY_PROFILES = "profiles_v1"
        private const val FORMAT_VERSION = 1
        private const val MAX_PROFILES = 50
    }
}
