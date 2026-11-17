package com.shev.agent.data

import androidx.room.withTransaction
import com.shev.agent.AgentPhase
import com.shev.agent.core.AgentVerificationEvidence
import com.shev.agent.core.AgentVerificationEvidenceKind
import com.shev.agent.core.AgentVerificationFailure
import com.shev.agent.core.AgentVerificationPolicy
import java.security.MessageDigest
import java.util.UUID

enum class PlanVerificationStatus {
    VERIFIED,
    TASK_NOT_FOUND,
    TASK_NOT_VERIFYING,
    STALE_TASK,
    PLAN_NOT_FOUND,
    EXECUTION_LEDGER_MISMATCH,
    STEP_NOT_SUCCEEDED,
    INSUFFICIENT_EVIDENCE,
    INVALID_TIMESTAMP
}

data class PlanVerificationOutcome(
    val status: PlanVerificationStatus,
    /** Raw one-use token; never persist or log this value. */
    val completionEvidenceToken: String? = null,
    val acceptedEvidenceRefs: List<String> = emptyList(),
    val failure: AgentVerificationFailure? = null,
    val failedStepId: String? = null
)

/**
 * Trusted, deterministic verifier for the local plan execution ledger.
 *
 * This verifier proves that every READ_ONLY step in the exact persisted plan
 * was dispatched and completed successfully by the kernel. It deliberately
 * refuses write, external-side-effect and critical steps until those tools
 * provide stronger file/remote/signed receipts.
 */
class RoomAgentPlanVerificationService(
    private val database: SheVAgentDatabase,
    private val planRepository: RoomAgentPlanRepository = RoomAgentPlanRepository(database)
) {
    suspend fun verifyAndIssueCompletionEvidence(
        taskId: String,
        expectedTaskRevision: Long,
        nowMillis: Long
    ): PlanVerificationOutcome {
        if (taskId.isBlank() || expectedTaskRevision < 0L ||
            nowMillis <= 0L || nowMillis > Long.MAX_VALUE - COMPLETION_EVIDENCE_TTL_MILLIS
        ) return PlanVerificationOutcome(PlanVerificationStatus.INVALID_TIMESTAMP)

        return database.withTransaction {
            val dao = database.agentDao()
            val task = dao.getTask(taskId)
                ?: return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.TASK_NOT_FOUND)
            if (task.revision != expectedTaskRevision) {
                return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.STALE_TASK)
            }
            if (task.phase != AgentPhase.VERIFYING.name) {
                return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.TASK_NOT_VERIFYING)
            }

            val persisted = planRepository.loadPlan(taskId)
                ?: return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.PLAN_NOT_FOUND)
            if (persisted.plan.taskId != taskId || persisted.plan.steps.isEmpty()) {
                return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.PLAN_NOT_FOUND)
            }

            val rows = dao.getAgentPlanStepExecutions(taskId)
            val expectedStepIds = persisted.plan.steps.map { it.id }.toSet()
            if (rows.size != expectedStepIds.size ||
                rows.map { it.stepId }.toSet() != expectedStepIds ||
                rows.any {
                    it.planRevision != persisted.plan.revision ||
                        it.stepRevision != persisted.stepRevisions[it.stepId] ||
                        it.taskRevision <= 0L ||
                        it.taskRevision >= task.revision
                }
            ) return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.EXECUTION_LEDGER_MISMATCH)

            val startRevision = rows.firstOrNull()?.taskRevision
                ?: return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.EXECUTION_LEDGER_MISMATCH)
            if (rows.any { it.taskRevision != startRevision } ||
                dao.countPlanStartEvents(taskId, startRevision) != 1
            ) return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.EXECUTION_LEDGER_MISMATCH)

            val byStepId = rows.associateBy { it.stepId }
            val evidence = mutableListOf<AgentVerificationEvidence>()
            for (step in persisted.plan.steps) {
                val execution = byStepId[step.id]
                    ?: return@withTransaction PlanVerificationOutcome(PlanVerificationStatus.EXECUTION_LEDGER_MISMATCH)
                if (execution.status != AgentPlanStepExecutionEntity.SUCCEEDED ||
                    execution.completedAtEpochMillis == null ||
                    execution.dispatchedAtEpochMillis == null ||
                    execution.resultCiphertext.isNullOrBlank() ||
                    execution.completedAtEpochMillis > nowMillis ||
                    execution.completedAtEpochMillis < execution.createdAtEpochMillis
                ) return@withTransaction PlanVerificationOutcome(
                    PlanVerificationStatus.STEP_NOT_SUCCEEDED,
                    failedStepId = step.id
                )

                val resultHash = sha256(execution.resultCiphertext)
                evidence += AgentVerificationEvidence(
                    stepId = step.id,
                    verifierId = TRUSTED_VERIFIER_ID,
                    kind = AgentVerificationEvidenceKind.KERNEL_EXECUTION_RECEIPT,
                    evidenceRef = "room://agent-plan/$taskId/${step.id}/${execution.invocationId}",
                    observedAtMillis = execution.completedAtEpochMillis,
                    sha256 = resultHash
                )
            }

            val decision = AgentVerificationPolicy.evaluate(
                plan = persisted.plan,
                evidence = evidence,
                trustedVerifierIds = setOf(TRUSTED_VERIFIER_ID),
                nowMillis = nowMillis
            )
            if (!decision.passed) {
                return@withTransaction PlanVerificationOutcome(
                    status = PlanVerificationStatus.INSUFFICIENT_EVIDENCE,
                    failure = decision.failure,
                    failedStepId = decision.stepId
                )
            }

            dao.deleteExpiredApprovalEvidence(nowMillis)
            val token = ApprovalEvidenceToken.create()
            val tokenHash = ApprovalEvidenceToken.digest(token)
            dao.insertApprovalEvidence(
                ApprovalEvidenceEntity(
                    evidenceHash = tokenHash,
                    taskId = taskId,
                    expectedRevision = expectedTaskRevision,
                    operation = COMPLETE_OPERATION,
                    issuedAtEpochMillis = nowMillis,
                    expiresAtEpochMillis = nowMillis + COMPLETION_EVIDENCE_TTL_MILLIS
                )
            )
            dao.appendEvent(
                AgentEventEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    eventType = "VERIFICATION_PASSED",
                    message = "Trusted local execution verification passed for ${decision.acceptedEvidenceRefs.size} plan steps",
                    createdAtEpochMillis = nowMillis,
                    revision = expectedTaskRevision,
                    idempotencyKey = "verification-passed:$taskId:$expectedTaskRevision:$tokenHash",
                    evidenceRef = tokenHash
                )
            )
            PlanVerificationOutcome(
                status = PlanVerificationStatus.VERIFIED,
                completionEvidenceToken = token,
                acceptedEvidenceRefs = decision.acceptedEvidenceRefs
            )
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TRUSTED_VERIFIER_ID = "room.agent_plan_execution"
        const val COMPLETE_OPERATION = "COMPLETE"
        const val COMPLETION_EVIDENCE_TTL_MILLIS = 120_000L
    }
}
