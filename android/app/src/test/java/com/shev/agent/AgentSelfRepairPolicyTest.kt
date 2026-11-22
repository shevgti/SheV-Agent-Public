package com.shev.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSelfRepairPolicyTest {

    private fun proposal(
        paths: List<String> = listOf("android/app/src/main/java/com/shev/agent/Example.kt"),
        diff: String = "--- a/Example.kt\n+++ b/Example.kt\n+// fix",
        tests: List<String> = listOf("./gradlew :app:testDebugUnitTest", "./gradlew :app:assembleDebug")
    ) = AgentSelfRepairProposal(
        id = "repair-001",
        summary = "Исправить обработку ошибки",
        rationale = "Тест воспроизводит ошибку; изменение устраняет её причину.",
        changedPaths = paths,
        unifiedDiff = diff,
        testPlan = tests
    )

    @Test fun ordinaryKotlinSourceChangeIsEligibleOnlyForSandboxReview() {
        val result = AgentSelfRepairPolicy.assess(proposal())
        assertEquals(AgentSelfRepairDecision.ACCEPT_FOR_SANDBOX_REVIEW, result.decision)
        assertTrue(result.acceptedForSandbox)
        assertTrue(result.reasons.single().contains("установка не разрешены"))
    }

    @Test fun manifestAndBuildFilesAreRejected() {
        val result = AgentSelfRepairPolicy.assess(
            proposal(paths = listOf("android/app/src/main/AndroidManifest.xml"))
        )
        assertEquals(AgentSelfRepairDecision.REJECT_UNSAFE_PATH, result.decision)
    }

    @Test fun pathTraversalAndDuplicatePathsAreRejected() {
        assertEquals(
            AgentSelfRepairDecision.REJECT_UNSAFE_PATH,
            AgentSelfRepairPolicy.assess(proposal(paths = listOf("../app/src/main/java/com/shev/agent/X.kt"))).decision
        )
        assertEquals(
            AgentSelfRepairDecision.REJECT_UNSAFE_PATH,
            AgentSelfRepairPolicy.assess(proposal(paths = listOf(
                "android/app/src/main/java/com/shev/agent/X.kt",
                "android/app/src/main/java/com/shev/agent/X.kt"
            ))).decision
        )
    }

    @Test fun databaseAndSecuritySourcesAreRejected() {
        assertEquals(
            AgentSelfRepairDecision.REJECT_UNSAFE_PATH,
            AgentSelfRepairPolicy.assess(proposal(paths = listOf("android/app/src/main/java/com/shev/agent/data/AgentDatabaseMigration.kt"))).decision
        )
        assertEquals(
            AgentSelfRepairDecision.REJECT_UNSAFE_PATH,
            AgentSelfRepairPolicy.assess(proposal(paths = listOf("android/app/src/main/java/com/shev/agent/security/TokenStore.kt"))).decision
        )
    }

    @Test fun missingTestsAndOversizedDiffAreRejected() {
        assertEquals(
            AgentSelfRepairDecision.REJECT_MISSING_TEST_PLAN,
            AgentSelfRepairPolicy.assess(proposal(tests = emptyList())).decision
        )
        assertEquals(
            AgentSelfRepairDecision.REJECT_TOO_LARGE,
            AgentSelfRepairPolicy.assess(proposal(diff = "x".repeat(AgentSelfRepairPolicy.MAX_DIFF_CHARS + 1))).decision
        )
    }

    @Test fun windowsPathSeparatorsAreNormalizedBeforeSafetyChecks() {
        val result = AgentSelfRepairPolicy.assess(
            proposal(paths = listOf("android\\app\\src\\main\\java\\com\\shev\\agent\\security\\Token.kt"))
        )
        assertEquals(AgentSelfRepairDecision.REJECT_UNSAFE_PATH, result.decision)
    }

    @Test fun protectedAuthenticationAndCryptoDirectoriesAreRejected() {
        val paths = listOf(
            "android/app/src/main/java/com/shev/agent/auth/AuthFlow.kt",
            "android/app/src/main/java/com/shev/agent/crypto/Cipher.kt",
            "android/app/src/main/java/com/shev/agent/permissions/Grant.kt"
        )
        paths.forEach { path ->
            assertEquals(path, AgentSelfRepairDecision.REJECT_UNSAFE_PATH,
                AgentSelfRepairPolicy.assess(proposal(paths = listOf(path))).decision)
        }
    }

    @Test fun tooManyChangedFilesAreRejected() {
        val paths = (1..AgentSelfRepairPolicy.MAX_CHANGED_FILES + 1).map {
            "android/app/src/main/java/com/shev/agent/File$it.kt"
        }
        assertEquals(
            AgentSelfRepairDecision.REJECT_TOO_LARGE,
            AgentSelfRepairPolicy.assess(proposal(paths = paths)).decision
        )
    }

    @Test fun oversizedSummaryAndRationaleAreRejected() {
        assertEquals(
            AgentSelfRepairDecision.REJECT_INVALID_PROPOSAL,
            AgentSelfRepairPolicy.assess(proposal().copy(summary = "x".repeat(AgentSelfRepairPolicy.MAX_SUMMARY_CHARS + 1))).decision
        )
        assertEquals(
            AgentSelfRepairDecision.REJECT_INVALID_PROPOSAL,
            AgentSelfRepairPolicy.assess(proposal().copy(rationale = "x".repeat(AgentSelfRepairPolicy.MAX_RATIONALE_CHARS + 1))).decision
        )
    }

    @Test fun absoluteAndBuildSystemPathsAreRejected() {
        val paths = listOf(
            "/android/app/src/main/java/com/shev/agent/Example.kt",
            "android/gradle/src/main/java/com/shev/agent/Example.kt",
            "android/app/src/main/java/com/shev/agent/buildSrc/Example.kt"
        )
        paths.forEach { path ->
            assertEquals(path, AgentSelfRepairDecision.REJECT_UNSAFE_PATH,
                AgentSelfRepairPolicy.assess(proposal(paths = listOf(path))).decision)
        }
    }

    @Test fun nonCanonicalPathSegmentsAreRejected() {
        val paths = listOf(
            "android/app/src/main/java/com/shev/agent/./Example.kt",
            "android//app/src/main/java/com/shev/agent/Example.kt",
            "android/app/src/main/java/com/shev/agent//Example.kt"
        )
        paths.forEach { path ->
            assertEquals(path, AgentSelfRepairDecision.REJECT_UNSAFE_PATH,
                AgentSelfRepairPolicy.assess(proposal(paths = listOf(path))).decision)
        }
    }

    @Test fun malformedProposalIsRejected() {
        val invalid = proposal().copy(summary = "")
        val result = AgentSelfRepairPolicy.assess(invalid)
        assertEquals(AgentSelfRepairDecision.REJECT_INVALID_PROPOSAL, result.decision)
        assertFalse(result.acceptedForSandbox)
    }
}
