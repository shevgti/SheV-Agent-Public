package com.shev.agent.tools

data class GitHubActionsRunEvidence(
    val repository: String,
    val runId: Long,
    val workflowPath: String,
    val headSha: String,
    val status: String,
    val conclusion: String
)

data class GitHubActionsJobEvidence(
    val name: String,
    val conclusion: String
)

object GitHubActionsEvidenceVerifier {
    fun isVerifiedSuccess(
        expectedRepository: String,
        expectedWorkflowPath: String,
        expectedHeadSha: String,
        expectedRunId: Long,
        run: GitHubActionsRunEvidence,
        jobs: List<GitHubActionsJobEvidence>,
        expectedJobsCount: Int = jobs.size
    ): Boolean {
        if (expectedRepository.isBlank() || expectedWorkflowPath.isBlank() ||
            !expectedHeadSha.matches(Regex("[0-9a-fA-F]{40}")) || expectedRunId <= 0
        ) return false

        if (run.repository != expectedRepository ||
            run.workflowPath != expectedWorkflowPath ||
            !run.headSha.equals(expectedHeadSha, ignoreCase = true) ||
            run.runId != expectedRunId ||
            run.status != "completed" ||
            run.conclusion != "success"
        ) return false

        if (expectedJobsCount <= 0 || jobs.size != expectedJobsCount) return false

        return jobs.isNotEmpty() &&
            jobs.all { it.name.isNotBlank() && it.conclusion == "success" }
    }
}
