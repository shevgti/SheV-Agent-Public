package com.shev.agent.tools

import org.junit.Assert.*
import org.junit.Test

class ToolUpdateBuilderTest {
    private val path = "projects/SheV-Agent/android/app/src/main/java/com/shev/agent/tools/CalendarReader.kt"

    @Test fun createsUpdateProposalOnlyForExistingFile() {
        val proposal = ToolUpdateBuilder.updateSourceProposal(
            "calendar.reader", path, "class Old", "class New", "Улучшить обработку", setOf(path)
        )
        assertEquals(ToolChangeKind.UPDATE, proposal.kind)
        assertEquals(ToolProposalStatus.READY_FOR_REVIEW, ToolDevelopmentPolicy.validate(proposal, setOf(path)).status)
    }

    @Test fun rejectsMissingTargetAndNoOpUpdate() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolUpdateBuilder.updateSourceProposal("calendar.reader", path, "old", "new", "Причина", emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolUpdateBuilder.updateSourceProposal("calendar.reader", path, "same", "same", "Причина", setOf(path))
        }
    }
}
