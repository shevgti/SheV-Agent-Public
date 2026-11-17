package com.shev.agent.tools

import com.shev.agent.AgentCapability
import org.junit.Assert.*
import org.junit.Test

class AgentPluginRegistryTest {
    private fun contribution(id: String = "calendar") = AgentPluginContribution(
        id = id,
        version = "1.0.0",
        tools = listOf(
            AgentToolDescriptor(
                id = "$id.read",
                title = "Чтение календаря",
                description = "Читает доступные записи",
                capabilities = setOf(AgentCapability.READ_LOCAL_DATA)
            ),
            AgentToolDescriptor(
                id = "$id.search",
                title = "Поиск календаря",
                description = "Ищет записи",
                capabilities = setOf(AgentCapability.ANALYZE_DATA)
            )
        )
    )

    @Test fun pluginRequiresExplicitUserApproval() {
        val tools = AgentToolRegistry()
        val plugins = AgentPluginRegistry(tools)
        assertEquals(AgentPluginActivationResult.USER_APPROVAL_REQUIRED,
            plugins.activate(contribution(), explicitlyApprovedByUser = false))
        assertTrue(tools.list().isEmpty())
    }

    @Test fun activationRegistersOnlyNamespacedBundledTools() {
        val tools = AgentToolRegistry()
        val plugins = AgentPluginRegistry(tools)
        assertEquals(AgentPluginActivationResult.ACTIVATED,
            plugins.activate(contribution(), explicitlyApprovedByUser = true))
        assertEquals(setOf("calendar.read", "calendar.search"), tools.list().map { it.id }.toSet())
    }

    @Test fun deactivationRemovesEveryPluginTool() {
        val tools = AgentToolRegistry()
        val plugins = AgentPluginRegistry(tools)
        plugins.activate(contribution(), explicitlyApprovedByUser = true)
        assertTrue(plugins.deactivate("calendar"))
        assertNull(tools.find("calendar.read"))
        assertNull(tools.find("calendar.search"))
        assertFalse(plugins.isActive("calendar"))
    }

    @Test fun duplicateActivationDoesNotReRegisterTools() {
        val plugins = AgentPluginRegistry(AgentToolRegistry())
        plugins.activate(contribution(), explicitlyApprovedByUser = true)
        assertEquals(AgentPluginActivationResult.ALREADY_ACTIVE,
            plugins.activate(contribution(), explicitlyApprovedByUser = true))
    }

    @Test fun conflictingToolIdRejectsWholePluginWithoutPartialRegistration() {
        val tools = AgentToolRegistry()
        tools.register(AgentToolDescriptor(
            "calendar.read", "Existing", "Existing descriptor",
            setOf(AgentCapability.READ_LOCAL_DATA)
        ))
        val plugins = AgentPluginRegistry(tools)
        assertEquals(AgentPluginActivationResult.TOOL_ID_CONFLICT,
            plugins.activate(contribution(), explicitlyApprovedByUser = true))
        assertNull(tools.find("calendar.search"))
        assertEquals(1, tools.list().size)
    }

    @Test fun rejectsToolOutsidePluginNamespace() {
        try {
            AgentPluginContribution(
                "calendar", "1.0.0",
                listOf(AgentToolDescriptor(
                    "other.read", "Bad", "Wrong namespace",
                    setOf(AgentCapability.READ_LOCAL_DATA)
                ))
            )
            fail("Expected namespacing validation")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
