package com.shev.agent.tools

import com.shev.agent.AgentCapability
import org.junit.Assert.*
import org.junit.Test

class AgentToolRegistryTest {
    @Test fun registersAndFindsToolByStableId() {
        val registry = AgentToolRegistry()
        val descriptor = AgentToolDescriptor(
            id = "local.files",
            title = "Локальные файлы",
            description = "Чтение разрешённых пользователем файлов",
            capabilities = setOf(AgentCapability.READ_LOCAL_DATA)
        )
        registry.register(descriptor)
        assertEquals(descriptor, registry.find("local.files"))
        assertEquals(1, registry.list().size)
    }

    @Test fun rejectsDuplicateToolIds() {
        val registry = AgentToolRegistry()
        val descriptor = AgentToolDescriptor(
            "local.files", "Файлы", "Описание", setOf(AgentCapability.READ_LOCAL_DATA)
        )
        registry.register(descriptor)
        try {
            registry.register(descriptor)
            fail("Повторная регистрация должна быть отклонена")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun findsToolsByCapabilityWithoutGrantingAccess() {
        val registry = AgentToolRegistry()
        registry.register(
            AgentToolDescriptor(
                "local.analyzer", "Анализатор", "Анализ локальных данных",
                setOf(AgentCapability.ANALYZE_DATA)
            )
        )
        registry.register(
            AgentToolDescriptor(
                "github.reader", "GitHub", "Чтение удалённых данных",
                setOf(AgentCapability.READ_REMOTE_DATA)
            )
        )
        assertEquals(listOf("local.analyzer"), registry.findSupporting(AgentCapability.ANALYZE_DATA).map { it.id })
        assertTrue(registry.findSupporting(AgentCapability.SPEND_MONEY).isEmpty())
    }

    @Test fun rejectsDescriptorsWithoutCapabilitiesOrDescriptions() {
        try {
            AgentToolDescriptor("local.empty", "Пустой", "Описание", emptySet())
            fail("Инструмент без capabilities должен быть отклонён")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
