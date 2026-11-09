package com.shev.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDevelopmentPolicyTest {
    private fun proposal(
        path: String = "projects/SheV-Agent/android/app/src/main/java/com/shev/agent/tools/CalendarTool.kt",
        content: String = "package com.shev.agent.tools\nclass CalendarTool",
        rationale: String = "Добавить адаптер календаря"
    ) = ToolChangeProposal("calendar.tool", ToolChangeKind.CREATE, path, content, rationale)

    @Test fun acceptsToolSourceProposalInAllowedDirectory() {
        assertEquals(ToolProposalStatus.READY_FOR_REVIEW, ToolDevelopmentPolicy.validate(proposal()).status)
    }

    @Test fun rejectsGradleAndWorkflowChanges() {
        assertEquals(
            ToolProposalStatus.UNSUPPORTED_PATH,
            ToolDevelopmentPolicy.validate(proposal(path = "projects/SheV-Agent/android/app/build.gradle.kts")).status
        )
        assertEquals(
            ToolProposalStatus.UNSUPPORTED_PATH,
            ToolDevelopmentPolicy.validate(proposal(path = ".github/workflows/build.yml")).status
        )
    }

    @Test fun rejectsPathTraversal() {
        assertEquals(
            ToolProposalStatus.UNSUPPORTED_PATH,
            ToolDevelopmentPolicy.validate(proposal(path = "projects/SheV-Agent/../secrets.kt")).status
        )
    }

    @Test fun rejectsEmbeddedCredentialLikeValues() {
        assertEquals(
            ToolProposalStatus.SECRET_LIKE_CONTENT,
            ToolDevelopmentPolicy.validate(
                proposal(content = """val api_key = "sk_test_verySecretValue123"""")
            ).status
        )
    }

    @Test fun rejectsPrivateKeyHeadersAndAwsAccessKeyIds() {
        assertEquals(
            ToolProposalStatus.SECRET_LIKE_CONTENT,
            ToolDevelopmentPolicy.validate(proposal(content = "-----BEGIN PRIVATE KEY-----")).status
        )
        assertEquals(
            ToolProposalStatus.SECRET_LIKE_CONTENT,
            ToolDevelopmentPolicy.validate(proposal(content = "val access = AKIA1234567890ABCDEF")).status
        )
    }

    @Test fun rejectsOversizedAndEmptyProposals() {
        assertEquals(
            ToolProposalStatus.CONTENT_TOO_LARGE,
            ToolDevelopmentPolicy.validate(proposal(content = "x".repeat(ToolDevelopmentPolicy.MAX_CONTENT_CHARS + 1))).status
        )
        assertEquals(
            ToolProposalStatus.EMPTY_CONTENT,
            ToolDevelopmentPolicy.validate(proposal(content = " ")).status
        )
    }

    @Test fun requiresReasonAndValidToolId() {
        assertEquals(
            ToolProposalStatus.EMPTY_RATIONALE,
            ToolDevelopmentPolicy.validate(proposal(rationale = " ")).status
        )
        assertEquals(
            ToolProposalStatus.INVALID_TOOL_ID,
            ToolDevelopmentPolicy.validate(proposal().copy(toolId = "Not Valid")).status
        )
    }


    @Test fun createRejectsExistingTarget() {
        val target = "projects/SheV-Agent/android/app/src/main/java/com/shev/agent/tools/CalendarTool.kt"
        assertEquals(
            ToolProposalStatus.TARGET_ALREADY_EXISTS,
            ToolDevelopmentPolicy.validate(proposal(), setOf(target)).status
        )
    }

    @Test fun updateRequiresExistingTarget() {
        val update = proposal().copy(kind = ToolChangeKind.UPDATE)
        assertEquals(
            ToolProposalStatus.TARGET_NOT_FOUND,
            ToolDevelopmentPolicy.validate(update).status
        )
        assertEquals(
            ToolProposalStatus.READY_FOR_REVIEW,
            ToolDevelopmentPolicy.validate(update, setOf(update.relativePath)).status
        )
    }

}
