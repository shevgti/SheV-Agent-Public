package com.shev.agent.tools

import org.junit.Assert.*
import org.junit.Test

class ImprovementWorkflowTest {
    private fun draft() = ImprovementRecord(id = "imp-001", summary = "Добавить безопасный адаптер")

    @Test fun cannotApplyDraftOrSkipTestsAndApproval() {
        val direct = ImprovementWorkflow.transition(draft(), ImprovementAction.APPLY)
        assertFalse(direct.accepted)
        assertEquals(ImprovementStage.DRAFT, direct.record.stage)

        val submitted = ImprovementWorkflow.transition(draft(), ImprovementAction.SUBMIT_FOR_REVIEW).record
        val withoutDiff = ImprovementWorkflow.transition(submitted, ImprovementAction.START_TESTS)
        assertFalse(withoutDiff.accepted)
    }

    @Test fun manualReportCannotMarkTestsPassedOrAllowApproval() {
        var record = draft()
        record = ImprovementWorkflow.transition(record, ImprovementAction.SUBMIT_FOR_REVIEW).record
        record = ImprovementWorkflow.transition(record, ImprovementAction.MARK_DIFF_REVIEWED).record
        record = ImprovementWorkflow.transition(record, ImprovementAction.START_TESTS).record
        record = ImprovementWorkflow.transition(
            record, ImprovementAction.SUBMIT_TEST_REPORT, "42000351839"
        ).record

        assertEquals(ImprovementStage.TEST_REPORT_SUBMITTED, record.stage)
        assertFalse(ImprovementWorkflow.transition(record, ImprovementAction.REQUEST_APPROVAL).accepted)
        assertFalse(ImprovementWorkflow.transition(record, ImprovementAction.APPLY).accepted)
    }

    @Test fun submittedReportIdIsNotTreatedAsVerifiedSuccess() {
        var record = ImprovementWorkflow.transition(draft(), ImprovementAction.SUBMIT_FOR_REVIEW).record
        record = ImprovementWorkflow.transition(record, ImprovementAction.MARK_DIFF_REVIEWED).record
        record = ImprovementWorkflow.transition(record, ImprovementAction.START_TESTS).record
        record = ImprovementWorkflow.transition(
            record, ImprovementAction.SUBMIT_TEST_REPORT, "41999699066"
        ).record

        assertEquals(ImprovementStage.TEST_REPORT_SUBMITTED, record.stage)
        assertFalse(ImprovementWorkflow.transition(record, ImprovementAction.REQUEST_APPROVAL).accepted)
        assertFalse(ImprovementWorkflow.transition(record, ImprovementAction.APPLY).accepted)
    }

    @Test fun failedTestsCannotRequestApproval() {
        var record = ImprovementWorkflow.transition(draft(), ImprovementAction.SUBMIT_FOR_REVIEW).record
        record = ImprovementWorkflow.transition(record, ImprovementAction.MARK_DIFF_REVIEWED).record
        record = ImprovementWorkflow.transition(record, ImprovementAction.START_TESTS).record
        record = ImprovementWorkflow.transition(
            record, ImprovementAction.RECORD_TEST_FAILURE, "ci-run-failed"
        ).record
        assertEquals(ImprovementStage.TESTS_FAILED, record.stage)
        assertFalse(ImprovementWorkflow.transition(record, ImprovementAction.REQUEST_APPROVAL).accepted)
    }

    @Test fun rejectedProposalCannotBeApplied() {
        val rejected = ImprovementWorkflow.transition(draft(), ImprovementAction.REJECT).record
        assertEquals(ImprovementStage.REJECTED, rejected.stage)
        assertFalse(ImprovementWorkflow.transition(rejected, ImprovementAction.APPLY).accepted)
    }

    @Test fun blankIdentityIsRejected() {
        val invalid = ImprovementRecord(id = " ", summary = "test")
        assertFalse(ImprovementWorkflow.transition(invalid, ImprovementAction.SUBMIT_FOR_REVIEW).accepted)
    }

    @Test fun verifiedEvidenceMustMatchRepositoryWorkflowAndRunId() {
        val sha = "e".repeat(40)
        val record = ImprovementRecord(
            id = "imp-004", summary = "Test", stage = ImprovementStage.TESTING,
            expectedHeadSha = sha
        )
        val valid = GitHubActionsVerificationResult.Verified(
            "shevgti/SheV-Memory",
            ".github/workflows/shev-agent-android.yml",
            555L, sha, 1
        )
        assertFalse(
            ImprovementWorkflow.transition(
                record, ImprovementAction.RECORD_TEST_SUCCESS, "554", valid
            ).accepted
        )
        assertFalse(
            ImprovementWorkflow.transition(
                record, ImprovementAction.RECORD_TEST_SUCCESS, "555",
                valid.copy(repository = "attacker/other")
            ).accepted
        )
        assertFalse(
            ImprovementWorkflow.transition(
                record, ImprovementAction.RECORD_TEST_SUCCESS, "555",
                valid.copy(workflowPath = ".github/workflows/untrusted.yml")
            ).accepted
        )
        assertFalse(
            ImprovementWorkflow.transition(
                record, ImprovementAction.RECORD_TEST_SUCCESS, "555",
                valid.copy(jobsCount = 0)
            ).accepted
        )
    }

}
