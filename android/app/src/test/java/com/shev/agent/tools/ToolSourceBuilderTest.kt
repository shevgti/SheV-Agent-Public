package com.shev.agent.tools

import org.junit.Assert.*
import org.junit.Test

class ToolSourceBuilderTest {
    @Test fun createsNonExecutingAdapterDraft() {
        val proposal = ToolSourceBuilder.createAdapterProposal(
            toolId = "calendar.reader",
            className = "CalendarReader",
            title = "Чтение календаря",
            description = "Черновик адаптера календаря",
            rationale = "Подготовить основу нового инструмента"
        )
        assertEquals(ToolChangeKind.CREATE, proposal.kind)
        assertTrue(proposal.relativePath.endsWith("/CalendarReader.kt"))
        assertTrue(proposal.proposedContent.contains("class CalendarReader : AgentTool"))
        assertTrue(proposal.proposedContent.contains("NOT_IMPLEMENTED"))
        assertEquals(ToolProposalStatus.READY_FOR_REVIEW, ToolDevelopmentPolicy.validate(proposal).status)
    }

    @Test fun rejectsUnsafeClassNames() {
        try {
            ToolSourceBuilder.createAdapterProposal(
                "calendar.reader", "../Secrets", "Название", "Описание", "Причина"
            )
            fail("Имя класса с путём должно быть отклонено")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun rejectsBlankMetadata() {
        try {
            ToolSourceBuilder.createAdapterProposal(
                "calendar.reader", "CalendarReader", " ", "Описание", "Причина"
            )
            fail("Пустое название должно быть отклонено")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
