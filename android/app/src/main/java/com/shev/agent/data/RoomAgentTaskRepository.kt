package com.shev.agent.data

import androidx.room.withTransaction
import com.shev.agent.AgentPhase
import com.shev.agent.AgentPlanJsonCodec
import com.shev.agent.AgentTask
import com.shev.agent.AgentTaskOrchestrator
import com.shev.agent.AgentWorkflowCommand
import com.shev.agent.AgentWorkflowRejection
import com.shev.agent.AgentWorkflowState

/**
 * Host-side verifier for one-shot approvals and verifier-produced completion evidence.
 * Implementations must validate a persisted, user-originated record bound to the exact
 * task, revision and operation. A model-provided string is not approval evidence.
 */
interface AgentTaskAuthorizationVerifier {
    suspend fun consumeApprovalEvidence(
        taskId: String,
        expectedRevision: Long,
        evidenceRef: String,
        planHash: String,
        nowMillis: Long
    ): Boolean

    suspend fun consumeCompletionEvidence(
        taskId: String,
        expectedRevision: Long,
        evidenceRef: String,
        planHash: String,
        nowMillis: Long
    ): Boolean
}

data class AgentTaskTransitionRequest(
    val taskId: String,
    val command: AgentWorkflowCommand,
    val expectedRevision: Long,
    val eventId: String,
    val idempotencyKey: String,
    val occurredAtEpochMillis: Long,
    val evidenceRef: String? = null,
    val planHash: String? = null
)

data class AgentTaskDraftRequest(
    val taskId: String,
    val title: String,
    val eventId: String,
    val idempotencyKey: String,
    val occurredAtEpochMillis: Long,
    val planJson: String = ""
)

sealed interface AgentTaskDraftResult {
    data class Created(val state: AgentWorkflowState) : AgentTaskDraftResult
    data class Replayed(val state: AgentWorkflowState) : AgentTaskDraftResult
    object InvalidRequest : AgentTaskDraftResult
    object Conflict : AgentTaskDraftResult
    object NotFound : AgentTaskDraftResult
}

enum class AgentTaskRepositoryRejection {
    INVALID_REQUEST,
    INVALID_REVISION,
    INVALID_PHASE,
    APPROVAL_REQUIRED,
    INVALID_APPROVAL_EVIDENCE,
    VERIFICATION_EVIDENCE_REQUIRED,
    INVALID_VERIFICATION_EVIDENCE,
    INVALID_STORED_PHASE,
    INVALID_PLAN
}

sealed interface AgentTaskRepositoryResult {
    data class Applied(val state: AgentWorkflowState) : AgentTaskRepositoryResult
    data class Replayed(val state: AgentWorkflowState) : AgentTaskRepositoryResult
    data class Rejected(val reason: AgentTaskRepositoryRejection) : AgentTaskRepositoryResult
    object NotFound : AgentTaskRepositoryResult
    object IdempotencyConflict : AgentTaskRepositoryResult
}

/**
 * Durable orchestration boundary.
 *
 * The task row and append-only event are committed in one Room transaction. Callers
 * must await Applied before dispatching any tool/action side effect. Replays never
 * dispatch effects; stale revisions and mismatched idempotency keys fail closed.
 */
class RoomAgentTaskRepository(
    private val database: SheVAgentDatabase,
    private val authorizationVerifier: AgentTaskAuthorizationVerifier
) {

    /**
     * Creates a user-authored task and its first lifecycle event atomically.
     * New tasks always enter WAITING_APPROVAL; this method never dispatches tools.
     */
    suspend fun createDraft(request: AgentTaskDraftRequest): AgentTaskDraftResult {
        val title = request.title.trim()
        val canonicalPlan = AgentPlanJsonCodec.canonicalize(request.planJson)
            ?: return AgentTaskDraftResult.InvalidRequest
        val planHash = AgentPlanJsonCodec.sha256(canonicalPlan)
        if (request.taskId.isBlank() ||
            title.isEmpty() ||
            title.length > 2_000 ||
            request.planJson.length > AgentPlanJsonCodec.MAX_PLAN_JSON_CHARS ||
            request.eventId.isBlank() ||
            request.idempotencyKey.isBlank() ||
            request.occurredAtEpochMillis < 0L
        ) return AgentTaskDraftResult.InvalidRequest

        return database.withTransaction {
            val dao = database.agentDao()
            val previous = dao.findEventByIdempotencyKey(request.idempotencyKey)
            if (previous != null) {
                val sameRequest = previous.taskId == request.taskId &&
                    previous.eventType == AgentWorkflowCommand.REQUEST_START.name &&
                    previous.revision == 1L
                if (!sameRequest) return@withTransaction AgentTaskDraftResult.Conflict
                val existing = dao.getTask(request.taskId)
                    ?: return@withTransaction AgentTaskDraftResult.NotFound
                if (existing.title != title || existing.planHash != planHash || existing.planJson != canonicalPlan) {
                    return@withTransaction AgentTaskDraftResult.Conflict
                }
                return@withTransaction try {
                    AgentTaskDraftResult.Replayed(existing.toWorkflowState())
                } catch (_: IllegalArgumentException) {
                    AgentTaskDraftResult.Conflict
                }
            }

            if (dao.getTask(request.taskId) != null) {
                return@withTransaction AgentTaskDraftResult.Conflict
            }

            val initialTask = AgentTaskEntity(
                id = request.taskId,
                title = title,
                phase = AgentPhase.PLANNING.name,
                requiresApproval = true,
                createdAtEpochMillis = request.occurredAtEpochMillis,
                updatedAtEpochMillis = request.occurredAtEpochMillis,
                revision = 0L,
                planJson = canonicalPlan,
                planHash = planHash
            )
            dao.insertTask(initialTask)

            val initialState = AgentWorkflowState(
                task = AgentTask(
                    id = request.taskId,
                    title = title,
                    phase = AgentPhase.PLANNING,
                    requiresApproval = true
                )
            )
            val transition = AgentTaskOrchestrator.transition(
                initialState,
                AgentWorkflowCommand.REQUEST_START,
                expectedRevision = 0L
            )
            check(transition.accepted) {
                "Initial task lifecycle transition was rejected; the transaction must roll back."
            }

            val next = transition.state
            dao.saveTask(
                initialTask.copy(
                    phase = next.task.phase.name,
                    revision = next.revision,
                    updatedAtEpochMillis = request.occurredAtEpochMillis
                )
            )
            dao.appendEvent(
                AgentEventEntity(
                    id = request.eventId,
                    taskId = request.taskId,
                    eventType = AgentWorkflowCommand.REQUEST_START.name,
                    message = "${AgentPhase.PLANNING.name}->${next.task.phase.name}",
                    createdAtEpochMillis = request.occurredAtEpochMillis,
                    revision = next.revision,
                    idempotencyKey = request.idempotencyKey
                )
            )
            AgentTaskDraftResult.Created(next)
        }
    }

    /**
     * Issues a short-lived nonce only in response to the explicit approval UI action,
     * persists only its digest, and consumes it in the same Room transaction as APPROVE.
     */
    suspend fun approveFromUser(
        taskId: String,
        expectedRevision: Long,
        eventId: String,
        idempotencyKey: String,
        occurredAtEpochMillis: Long
    ): AgentTaskRepositoryResult {
        if (taskId.isBlank() || eventId.isBlank() || idempotencyKey.isBlank() ||
            expectedRevision < 0L || expectedRevision == Long.MAX_VALUE ||
            occurredAtEpochMillis < 0L ||
            occurredAtEpochMillis > Long.MAX_VALUE - APPROVAL_TTL_MILLIS
        ) return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_REQUEST)

        return database.withTransaction {
            val dao = database.agentDao()
            val previous = dao.findEventByIdempotencyKey(idempotencyKey)
            if (previous != null) {
                val sameRequest = previous.taskId == taskId &&
                    previous.eventType == AgentWorkflowCommand.APPROVE.name &&
                    previous.revision == expectedRevision + 1L &&
                    previous.evidenceRef?.matches(Regex("[0-9a-f]{64}")) == true
                if (!sameRequest) return@withTransaction AgentTaskRepositoryResult.IdempotencyConflict
                val current = dao.getTask(taskId) ?: return@withTransaction AgentTaskRepositoryResult.NotFound
                if (current.planJson.isBlank() || current.planHash.isBlank() ||
                    AgentPlanJsonCodec.canonicalize(current.planJson) != current.planJson ||
                    AgentPlanJsonCodec.sha256(current.planJson) != current.planHash
                ) return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
                return@withTransaction try {
                    AgentTaskRepositoryResult.Replayed(current.toWorkflowState())
                } catch (_: IllegalArgumentException) {
                    AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_STORED_PHASE)
                }
            }

            val stored = dao.getTask(taskId) ?: return@withTransaction AgentTaskRepositoryResult.NotFound
            if (stored.planJson.isBlank() || stored.planHash.isBlank() ||
                AgentPlanJsonCodec.canonicalize(stored.planJson) != stored.planJson ||
                AgentPlanJsonCodec.sha256(stored.planJson) != stored.planHash
            ) return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            if (stored.revision != expectedRevision) {
                return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_REVISION)
            }
            if (stored.phase != AgentPhase.WAITING_APPROVAL.name) {
                return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PHASE)
            }

            dao.deleteExpiredApprovalEvidence(occurredAtEpochMillis)
            val nonce = ApprovalEvidenceToken.create()
            val evidenceHash = ApprovalEvidenceToken.digest(nonce)
            dao.insertApprovalEvidence(
                ApprovalEvidenceEntity(
                    evidenceHash = evidenceHash,
                    taskId = taskId,
                    expectedRevision = expectedRevision,
                    operation = APPROVE_OPERATION,
                    planHash = stored.planHash,
                    issuedAtEpochMillis = occurredAtEpochMillis,
                    expiresAtEpochMillis = occurredAtEpochMillis + APPROVAL_TTL_MILLIS
                )
            )
            val result = transitionWithinTransaction(
                AgentTaskTransitionRequest(
                    taskId = taskId,
                    command = AgentWorkflowCommand.APPROVE,
                    expectedRevision = expectedRevision,
                    eventId = eventId,
                    idempotencyKey = idempotencyKey,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    evidenceRef = nonce,
                    planHash = stored.planHash
                )
            )
            if (result !is AgentTaskRepositoryResult.Applied) {
                dao.deleteApprovalEvidence(evidenceHash)
            }
            result
        }
    }

    /**
     * Atomically moves an approved, hash-matched plan to RUNNING and creates its step ledger.
     * No tool is dispatched inside this transaction.
     */
    suspend fun startApprovedExecution(
        taskId: String,
        expectedRevision: Long,
        planHash: String,
        eventId: String,
        idempotencyKey: String,
        occurredAtEpochMillis: Long
    ): AgentTaskRepositoryResult {
        if (taskId.isBlank() || planHash.isBlank() || eventId.isBlank() ||
            idempotencyKey.isBlank() || expectedRevision < 0L ||
            expectedRevision == Long.MAX_VALUE || occurredAtEpochMillis < 0L
        ) return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_REQUEST)

        return database.withTransaction {
            val dao = database.agentDao()
            val stored = dao.getTask(taskId) ?: return@withTransaction AgentTaskRepositoryResult.NotFound
            val canonicalPlan = AgentPlanJsonCodec.canonicalize(stored.planJson)
                ?: return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            if (canonicalPlan != stored.planJson || stored.planHash != planHash ||
                AgentPlanJsonCodec.sha256(canonicalPlan) != planHash
            ) return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)

            val plan = AgentPlanJsonCodec.decode(canonicalPlan)
                ?: return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            val previousStart = dao.findEventByIdempotencyKey(idempotencyKey)
            if (previousStart == null && stored.phase != AgentPhase.APPROVED.name) {
                return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PHASE)
            }
            if (previousStart == null && stored.revision != expectedRevision) {
                return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_REVISION)
            }
            val existingSteps = dao.getTaskSteps(taskId)
            if (previousStart != null && existingSteps.isEmpty()) {
                return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            }
            if (existingSteps.isNotEmpty()) {
                val samePlan = existingSteps.size == plan.steps.size &&
                    existingSteps.map { it.stepId to it.planHash } ==
                    plan.steps.map { it.id to planHash }
                if (!samePlan) return@withTransaction AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            } else {
                dao.insertTaskSteps(plan.steps.mapIndexed { index, step ->
                    AgentTaskStepEntity(
                        taskId = taskId,
                        stepId = step.id,
                        position = index,
                        title = step.title,
                        toolId = step.toolId,
                        planHash = planHash,
                        status = AgentTaskStepEntity.PENDING
                    )
                })
            }

            transitionWithinTransaction(
                AgentTaskTransitionRequest(
                    taskId = taskId,
                    command = AgentWorkflowCommand.START_EXECUTION,
                    expectedRevision = expectedRevision,
                    eventId = eventId,
                    idempotencyKey = idempotencyKey,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    planHash = planHash
                )
            )
        }
    }

    suspend fun transition(request: AgentTaskTransitionRequest): AgentTaskRepositoryResult {
        // START_EXECUTION must go through startApprovedExecution so the plan-bound step ledger
        // and the lifecycle event are committed atomically.
        if (request.command == AgentWorkflowCommand.START_EXECUTION) {
            return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_REQUEST)
        }
        if (request.taskId.isBlank() ||
            request.eventId.isBlank() ||
            request.idempotencyKey.isBlank() ||
            request.expectedRevision < 0L ||
            request.expectedRevision == Long.MAX_VALUE ||
            request.occurredAtEpochMillis < 0L
        ) return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_REQUEST)

        return database.withTransaction { transitionWithinTransaction(request) }
    }

    private suspend fun transitionWithinTransaction(
        request: AgentTaskTransitionRequest
    ): AgentTaskRepositoryResult {
        val dao = database.agentDao()
        val eventEvidenceRef = if (request.command == AgentWorkflowCommand.APPROVE) {
            request.evidenceRef?.let { runCatching { ApprovalEvidenceToken.digest(it) }.getOrNull() }
        } else {
            request.evidenceRef
        }
        if (request.command == AgentWorkflowCommand.APPROVE && eventEvidenceRef == null) {
            return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_APPROVAL_EVIDENCE)
        }

        val previousEvent = dao.findEventByIdempotencyKey(request.idempotencyKey)
        if (previousEvent != null) {
            val sameRequest =
                previousEvent.taskId == request.taskId &&
                    previousEvent.eventType == request.command.name &&
                    previousEvent.revision == request.expectedRevision + 1L &&
                    previousEvent.evidenceRef == eventEvidenceRef
            if (!sameRequest) return AgentTaskRepositoryResult.IdempotencyConflict
            val current = dao.getTask(request.taskId) ?: return AgentTaskRepositoryResult.NotFound
            return try {
                AgentTaskRepositoryResult.Replayed(current.toWorkflowState())
            } catch (_: IllegalArgumentException) {
                AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_STORED_PHASE)
            }
        }

        val stored = dao.getTask(request.taskId) ?: return AgentTaskRepositoryResult.NotFound

        if (request.command == AgentWorkflowCommand.START_EXECUTION) {
            val planHash = request.planHash
                ?: return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            if (stored.planHash != planHash || stored.planJson.isBlank() ||
                AgentPlanJsonCodec.canonicalize(stored.planJson) != stored.planJson ||
                AgentPlanJsonCodec.sha256(stored.planJson) != planHash
            ) return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            val plan = AgentPlanJsonCodec.decode(stored.planJson)
                ?: return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            val ledger = dao.getTaskSteps(request.taskId)
            if (ledger.size != plan.steps.size ||
                ledger.map { it.stepId to it.planHash } != plan.steps.map { it.id to planHash }
            ) return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
        }

        if (request.command == AgentWorkflowCommand.COMPLETE) {
            val evidence = request.evidenceRef
                ?: return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.VERIFICATION_EVIDENCE_REQUIRED)
            if (evidence.isBlank() || !authorizationVerifier.isCompletionEvidenceValid(
                    request.taskId, request.expectedRevision, evidence
                )) {
                return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_VERIFICATION_EVIDENCE)
            }
        }

        val currentState = try {
            stored.toWorkflowState()
        } catch (_: IllegalArgumentException) {
            return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_STORED_PHASE)
        }

        val transition = AgentTaskOrchestrator.transition(
            currentState,
            request.command,
            expectedRevision = request.expectedRevision
        )
        if (!transition.accepted) {
            val reason = when (transition.rejection) {
                AgentWorkflowRejection.INVALID_REVISION -> AgentTaskRepositoryRejection.INVALID_REVISION
                AgentWorkflowRejection.APPROVAL_REQUIRED -> AgentTaskRepositoryRejection.APPROVAL_REQUIRED
                else -> AgentTaskRepositoryRejection.INVALID_PHASE
            }
            return AgentTaskRepositoryResult.Rejected(reason)
        }

        if (request.command == AgentWorkflowCommand.APPROVE) {
            val evidence = request.evidenceRef
                ?: return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.APPROVAL_REQUIRED)
            val planHash = request.planHash
                ?: return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            if (stored.planHash != planHash || stored.planJson.isBlank() ||
                AgentPlanJsonCodec.sha256(stored.planJson) != planHash
            ) return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_PLAN)
            if (!authorizationVerifier.consumeApprovalEvidence(
                    request.taskId, request.expectedRevision, evidence, planHash, request.occurredAtEpochMillis
                )) {
                return AgentTaskRepositoryResult.Rejected(AgentTaskRepositoryRejection.INVALID_APPROVAL_EVIDENCE)
            }
        }

        val next = transition.state
        dao.saveTask(
            stored.copy(
                phase = next.task.phase.name,
                revision = next.revision,
                resumePhase = next.resumePhase?.name,
                updatedAtEpochMillis = request.occurredAtEpochMillis
            )
        )
        dao.appendEvent(
            AgentEventEntity(
                id = request.eventId,
                taskId = request.taskId,
                eventType = request.command.name,
                message = "${currentState.task.phase.name}->${next.task.phase.name}",
                createdAtEpochMillis = request.occurredAtEpochMillis,
                revision = next.revision,
                idempotencyKey = request.idempotencyKey,
                evidenceRef = eventEvidenceRef
            )
        )
        return AgentTaskRepositoryResult.Applied(next)
    }

    private companion object {
        const val APPROVAL_TTL_MILLIS = 120_000L
        const val APPROVE_OPERATION = "APPROVE"
    }

    private fun AgentTaskEntity.toWorkflowState(): AgentWorkflowState =
        AgentWorkflowState(
            task = AgentTask(
                id = id,
                title = title,
                phase = AgentPhase.valueOf(phase),
                requiresApproval = requiresApproval
            ),
            resumePhase = resumePhase?.let(AgentPhase::valueOf),
            revision = revision
        )
}
