package com.shev.agent

/**
 * Safety gate for agent-authored source changes.
 *
 * This policy deliberately does not apply patches or load generated code into the running
 * process. It only classifies a proposal. A host-controlled workflow must create an isolated
 * worktree, run checks, obtain user approval, and install a signed build through normal Android
 * update mechanisms.
 */
data class AgentSelfRepairProposal(
    val id: String,
    val summary: String,
    val rationale: String,
    val changedPaths: List<String>,
    val unifiedDiff: String,
    val testPlan: List<String>
)

enum class AgentSelfRepairDecision {
    REJECT_INVALID_PROPOSAL,
    REJECT_UNSAFE_PATH,
    REJECT_TOO_LARGE,
    REJECT_MISSING_TEST_PLAN,
    ACCEPT_FOR_SANDBOX_REVIEW
}

data class AgentSelfRepairAssessment(
    val decision: AgentSelfRepairDecision,
    val reasons: List<String>
) {
    val acceptedForSandbox: Boolean
        get() = decision == AgentSelfRepairDecision.ACCEPT_FOR_SANDBOX_REVIEW
}

/**
 * Fail-closed policy. "Accepted" means eligible for isolated validation only, never
 * permission to modify the installed app or execute arbitrary generated code.
 */
object AgentSelfRepairPolicy {
    const val MAX_CHANGED_FILES = 20
    const val MAX_DIFF_CHARS = 40_000
    const val MAX_SUMMARY_CHARS = 240
    const val MAX_RATIONALE_CHARS = 2_000

    private val allowedPath = Regex(
        """android/app/src/(main|test)/java/[A-Za-z0-9_./-]+\.kt"""
    )
    private val forbiddenSegments = setOf(
        ".github", "gradle", "buildSrc", "keystore", "secrets", "signing"
    )
    private val protectedNames = setOf(
        "AndroidManifest.xml", "build.gradle", "build.gradle.kts",
        "settings.gradle", "settings.gradle.kts", "gradle.properties"
    )
    private val protectedCodePatterns = listOf(
        Regex("""(^|/)security/""", RegexOption.IGNORE_CASE),
        Regex("""(^|/)(auth|crypto|permissions?)/""", RegexOption.IGNORE_CASE),
        Regex("""(^|/).*(Database|Migration|RoomDatabase).*\.kt$""", RegexOption.IGNORE_CASE)
    )

    fun assess(proposal: AgentSelfRepairProposal): AgentSelfRepairAssessment {
        if (proposal.id.isBlank() ||
            proposal.summary.isBlank() ||
            proposal.summary.length > MAX_SUMMARY_CHARS ||
            proposal.rationale.isBlank() ||
            proposal.rationale.length > MAX_RATIONALE_CHARS ||
            proposal.unifiedDiff.isBlank() ||
            proposal.changedPaths.isEmpty()
        ) return rejected(AgentSelfRepairDecision.REJECT_INVALID_PROPOSAL, "Не хватает обязательных данных исправления.")

        if (proposal.changedPaths.size > MAX_CHANGED_FILES ||
            proposal.unifiedDiff.length > MAX_DIFF_CHARS
        ) return rejected(AgentSelfRepairDecision.REJECT_TOO_LARGE, "Изменение превышает установленный лимит.")

        if (proposal.testPlan.isEmpty() || proposal.testPlan.any { it.isBlank() }) {
            return rejected(AgentSelfRepairDecision.REJECT_MISSING_TEST_PLAN, "Для исправления обязателен конкретный план проверок.")
        }

        val normalized = proposal.changedPaths.map { it.replace('\\', '/') }
        val unsafe = normalized.any { path ->
            path.startsWith("/") ||
                path.split("/").any { it == ".." || it in forbiddenSegments } ||
                path.substringAfterLast("/") in protectedNames ||
                protectedCodePatterns.any { it.containsMatchIn(path) } ||
                !allowedPath.matches(path)
        }
        if (unsafe || normalized.distinct().size != normalized.size) {
            return rejected(AgentSelfRepairDecision.REJECT_UNSAFE_PATH, "Путь защищён, недопустим или указан повторно.")
        }

        return AgentSelfRepairAssessment(
            AgentSelfRepairDecision.ACCEPT_FOR_SANDBOX_REVIEW,
            listOf("Разрешена только изолированная проверка исходного кода; применение и установка не разрешены.")
        )
    }

    private fun rejected(
        decision: AgentSelfRepairDecision,
        reason: String
    ) = AgentSelfRepairAssessment(decision, listOf(reason))
}
