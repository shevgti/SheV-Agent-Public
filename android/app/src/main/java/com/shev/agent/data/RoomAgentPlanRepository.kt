package com.shev.agent.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.shev.agent.core.AgentPlan
import com.shev.agent.core.PlanStep
import com.shev.agent.core.TaskBudget

enum class AgentPlanSaveResult {
    STORED,
    TASK_NOT_FOUND,
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

            val advanced = dao.advanceTaskRevision(
                taskId = plan.taskId,
                expectedRevision = task.revision,
                newRevision = task.revision + 1L,
                updatedAtEpochMillis = savedAtEpochMillis
            )
            if (advanced != 1) {
                return@withTransaction AgentPlanSaveResult.STALE_REVISION
            }

            val previousSteps = dao.getAgentPlanSteps(plan.taskId).associateBy { it.stepId }
            val stepRows = plan.steps.map { step ->
                val canonicalStep = canonicalStepJson(step)
                val previous = previousSteps[step.id]
                val nextStepRevision = when {
                    previous == null -> 0L
                    previous.canonicalStepJson == canonicalStep -> previous.stepRevision
                    previous.stepRevision == Long.MAX_VALUE -> return@withTransaction AgentPlanSaveResult.STALE_REVISION
                    else -> previous.stepRevision + 1L
                }
                AgentPlanStepEntity(
                    taskId = plan.taskId,
                    stepId = step.id,
                    planRevision = plan.revision,
                    stepRevision = nextStepRevision,
                    canonicalStepJson = canonicalStep
                )
            }

            dao.saveAgentPlan(
                AgentPlanEntity(
                    taskId = plan.taskId,
                    planRevision = plan.revision,
                    canonicalPlanJson = canonicalPlanJson(plan),
                    savedAtEpochMillis = savedAtEpochMillis
                )
            )
            dao.deleteAgentPlanSteps(plan.taskId)
            dao.insertAgentPlanSteps(stepRows)
            AgentPlanSaveResult.STORED
        }
    }

    suspend fun loadPlan(taskId: String): PersistedAgentPlan? {
        if (taskId.isBlank()) return null
        val dao = database.agentDao()
        val stored = dao.getAgentPlan(taskId) ?: return null
        val decoded = runCatching {
            gson.fromJson(stored.canonicalPlanJson, AgentPlan::class.java)
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
                persisted.canonicalStepJson != canonicalStepJson(step)
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
