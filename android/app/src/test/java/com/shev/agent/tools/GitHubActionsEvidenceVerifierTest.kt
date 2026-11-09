package com.shev.agent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubActionsEvidenceVerifierTest {
    private val repository = "shevgti/SheV-Memory"
    private val workflow = ".github/workflows/shev-agent-android.yml"
    private val sha = "a".repeat(40)
    private val runId = 42L
    private val successfulJobs = listOf(
        GitHubActionsJobEvidence("build", "success"),
        GitHubActionsJobEvidence("unit-tests", "success")
    )

    private fun run(
        repository: String = this.repository,
        workflowPath: String = workflow,
        headSha: String = sha,
        id: Long = runId,
        status: String = "completed",
        conclusion: String = "success"
    ) = GitHubActionsRunEvidence(repository, id, workflowPath, headSha, status, conclusion)

    private fun verify(
        evidence: GitHubActionsRunEvidence = run(),
        jobs: List<GitHubActionsJobEvidence> = successfulJobs,
        expectedRepository: String = repository,
        expectedWorkflow: String = workflow,
        expectedSha: String = sha,
        expectedRunId: Long = runId,
        expectedJobsCount: Int = jobs.size
    ) = GitHubActionsEvidenceVerifier.isVerifiedSuccess(
        expectedRepository, expectedWorkflow, expectedSha, expectedRunId, evidence, jobs, expectedJobsCount
    )

    @Test fun acceptsExactCompletedSuccessfulRunWithSuccessfulJobs() {
        assertTrue(verify())
    }

    @Test fun rejectsDifferentRepositoryWorkflowShaAndRunId() {
        assertFalse(verify(evidence = run(repository = "someone/else")))
        assertFalse(verify(evidence = run(workflowPath = ".github/workflows/other.yml")))
        assertFalse(verify(evidence = run(headSha = "different")))
        assertFalse(verify(evidence = run(id = runId + 1)))
    }

    @Test fun rejectsNonCompletedAndUnsuccessfulRunConclusions() {
        assertFalse(verify(evidence = run(status = "in_progress")))
        assertFalse(verify(evidence = run(conclusion = "failure")))
        assertFalse(verify(evidence = run(conclusion = "cancelled")))
        assertFalse(verify(evidence = run(conclusion = "timed_out")))
    }

    @Test fun rejectsEmptyOrPartiallyFailedJobs() {
        assertFalse(verify(jobs = emptyList()))
        assertFalse(verify(jobs = successfulJobs + GitHubActionsJobEvidence("lint", "failure")))
        assertFalse(verify(jobs = successfulJobs + GitHubActionsJobEvidence("lint", "skipped")))
        assertFalse(verify(jobs = successfulJobs + GitHubActionsJobEvidence("", "success")))
    }

    @Test fun rejectsIncompletePagedJobEvidence() {
        assertFalse(verify(expectedJobsCount = successfulJobs.size + 1))
        assertFalse(verify(expectedJobsCount = 0))
    }

    @Test fun rejectsMissingExpectedIdentity() {
        assertFalse(verify(expectedRepository = " "))
        assertFalse(verify(expectedWorkflow = ""))
        assertFalse(verify(expectedSha = ""))
        assertFalse(verify(expectedSha = "abc123"))
        assertFalse(verify(expectedRunId = 0L))
    }
}
