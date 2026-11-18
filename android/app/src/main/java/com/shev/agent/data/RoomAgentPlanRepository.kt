package com.shev.agent.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import com.shev.agent.core.McpInvocationExecutionContext
import com.shev.agent.AgentPhase
import java.util.UUID

enum class AgentPlanSaveResult {
    STORED,
    TASK_NOT_FOUND,
    INVALID_TASK_PHASE,
    STALE_REVISION,
    INVALID_TIMESTAMP
}

data class PersistedAgentPlan(
    val plan: AgentPlan,
    val stepRevisions: Map<String, Long>,
    val savedAtEpochMillis: Long
)

/**
 * Persists the new core DAG contract as the canonical plan source of truth.
 *
 * The task revision is advanced atomically with plan replacement. Each unchanged
 * step keeps its revision; changed/new steps receive a new revision. This lets
 * approval evidence bind to both task and step revisions after process restart.
 */
class RoomAgentPlanRepository(
    private val database: SheVAgentDatabase
) {
    private val gson = Gson()

    suspend fun saveValidatedPlan(
        plan: AgentPlan,
        savedAtEpochMillis: Long
    ): AgentPlanSaveResult {
        if (savedAtEpochMillis <= 0L) return AgentPlanSaveResult.INVALID_TIMESTAMP

        return database.withTransaction {
            val dao = database.agentDao()
            val task = dao.getTask(plan.taskId)
                ?: return@withTransaction AgentPlanSaveResult.TASK_NOT_FOUND
            if (task.phase !in setOf("PLANNING", "WAITING_APPROVAL")) {
                return@withTransaction AgentPlanSaveResult.INVALID_TASK_PHASE
            }
            val previousPlan = dao.getAgentPlan(plan.taskId)
            val expectedPlanRevision = if (previousPlan == null) {
                0L
            } else {
                if (previousPlan.planRevision == Long.MAX_VALUE) {
                    return@withTransaction AgentPlanSaveResult.STALE_REVISION
                }
                previousPlan.planRevision + 1L
            }
            if (plan.revision != expectedPlanRevision || task.revision == Long.MAX_VALUE) {
                return@withTransaction AgentPlanSaveResult.STALE_REVISION
            }

            val previousSteps = dao.getAgentPlanSteps(plan.taskId).associateBy { it.stepId }
            val stepRows = plan.steps.map { step ->
                val canonicalStep = canonicalStepJson(step)
                val previous = previousSteps[step.id]
                val previousCanonical = previous?.let {
                    runCatching { LocalPlanCipher.decrypt(it.canonicalStepJson) }.getOrNull()
                }
                val nextStepRevision = when {
                    previous == null -> 0L
                    previousCanonical == canonicalStep -> previous.stepRevision
                    previous.stepRevision == Long.MAX_VALUE -> return@withTransaction AgentPlanSaveResult.STALE_REVISION
                    else -> previous.stepRevision + 1L
                }
                AgentPlanStepEntity(
                    taskId = plan.taskId,
                    stepId = step.id,
                    planRevision = plan.revision,
                    stepRevision = nextStepRevision,
                    canonicalStepJson = LocalPlanCipher.encrypt(canonicalStep)
                )
            }

            val advanced = dao.advanceTaskRevision(
                taskId = plan.taskId,
                expectedRevision = task.revision,
                newRevision = task.revision + 1L,
                updatedAtEpochMillis = savedAtEpochMillis
            )
            if (advanced != 1) {
                return@withTransaction AgentPlanSaveResult.STALE_REVISION
            }

            dao.saveAgentPlan(
                AgentPlanEntity(
                    taskId = plan.taskId,
                    planRevision = plan.revision,
                    canonicalPlanJson = LocalPlanCipher.encrypt(canonicalPlanJson(plan)),
                    savedAtEpochMillis = savedAtEpochMillis
                )
            )
            dao.deleteAgentPlanSteps(plan.taskId)
            dao.insertAgentPlanSteps(stepRows)
            dao.appendEvent(
                AgentEventEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = plan.taskId,
                    eventType = "PLAN_PROPOSED",
                    message = plan.summary.take(2_000),
                    createdAtEpochMillis = savedAtEpochMillis,
                    revision = task.revision + 1L,
                    idempotencyKey = "plan-proposed:${plan.taskId}:${plan.revision}"
                )
            )
            AgentPlanSaveResult.STORED
        }
    }

    /**
     * Creates an execution context only from the current persisted RUNNING task,
     * canonical plan and exact step revision. Model arguments cannot supply these IDs.
     */
    suspend fun createExecutionContext(
        taskId: String,
        stepId: String,
        invocationId: String
    ): McpInvocationExecutionContext? {
        if (taskId.isBlank() || stepId.isBlank()) return null
        return database.withTransaction {
            val dao = database.agentDao()
            val task = dao.getTask(taskId) ?: return@withTransaction null
            if (task.phase != AgentPhase.RUNNING.name) return@withTransaction null
            val persisted = loadPlan(taskId) ?: return@withTransaction null
            val stepRevision = persisted.stepRevisions[stepId] ?: return@withTransaction null
            runCatching {
                McpInvocationExecutionContext(
                    invocationId = invocationId,
                    taskId = taskId,
                    taskRevision = task.revision,
                    planRevision = persisted.plan.revision,
                    stepId = stepId,
                    stepRevision = stepRevision
                )
            }.getOrNull()
        }
    }

    suspend fun loadPlan(taskId: String): PersistedAgentPlan? {
        if (taskId.isBlank()) return null
        val dao = database.agentDao()
        val stored = dao.getAgentPlan(taskId) ?: return null
        val plainPlanJson = runCatching {
            LocalPlanCipher.decrypt(stored.canonicalPlanJson)
        }.getOrNull() ?: return null
        val decoded = runCatching {
            gson.fromJson(plainPlanJson, AgentPlan::class.java)
        }.getOrNull() ?: return null
        val plan = runCatching {
            AgentPlan(
                taskId = decoded.taskId,
                revision = decoded.revision,
                summary = decoded.summary,
                steps = decoded.steps.map { it.copy(dependsOn = it.dependsOn.toSet()) },
                budget = decoded.budget
            )
        }.getOrNull() ?: return null
        if (plan.taskId != taskId || plan.revision != stored.planRevision) return null
        val steps = dao.getAgentPlanSteps(taskId)
        val stepRowsById = steps.associateBy { it.stepId }
        if (steps.size != plan.steps.size ||
            steps.any { it.planRevision != stored.planRevision } ||
            plan.steps.any { step ->
                val persisted = stepRowsById[step.id] ?: return@any true
                runCatching { LocalPlanCipher.decrypt(persisted.canonicalStepJson) }.getOrNull() != canonicalStepJson(step)
            }
        ) return null
        return PersistedAgentPlan(
            plan = plan,
            stepRevisions = steps.associate { it.stepId to it.stepRevision },
            savedAtEpochMillis = stored.savedAtEpochMillis
        )
    }

    private fun canonicalPlanJson(plan: AgentPlan): String {
        val root = JsonObject().apply {
            addProperty("taskId", plan.taskId)
            addProperty("revision", plan.revision)
            addProperty("summary", plan.summary)
            add("budget", JsonObject().apply {
                addProperty("maxSteps", plan.budget.maxSteps)
                addProperty("maxRetriesPerStep", plan.budget.maxRetriesPerStep)
                addProperty("maxWallTimeMillis", plan.budget.maxWallTimeMillis)
                addProperty("maxModelCalls", plan.budget.maxModelCalls)
                addProperty("maxOutputCharsPerStep", plan.budget.maxOutputCharsPerStep)
            })
            add("steps", JsonArray().apply {
                plan.steps.forEach { step -> add(gson.fromJson(canonicalStepJson(step), JsonObject::class.java)) }
            })
        }
        return gson.toJson(root)
    }

    private fun canonicalStepJson(step: PlanStep): String = gson.toJson(JsonObject().apply {
        addProperty("id", step.id)
        addProperty("toolId", step.toolId)
        addProperty("argumentsJson", step.argumentsJson)
        add("dependsOn", JsonArray().apply { step.dependsOn.sorted().forEach { add(it) } })
        addProperty("risk", step.risk.name)
        addProperty("purpose", step.purpose)
        addProperty("expectedOutcome", step.expectedOutcome)
        addProperty("timeoutMillis", step.timeoutMillis)
    })
}
