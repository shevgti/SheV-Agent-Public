package com.shev.agent.core

import java.util.UUID

/** User intent entering the kernel. UI gateways must not execute tools directly. */
data class AgentRequest(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val source: RequestSource,
    val createdAtMillis: Long,
    val deadlineAtMillis: Long? = null
) {
    init {
        require(id.isNotBlank())
        require(text.isNotBlank() && text.length <= MAX_REQUEST_CHARS)
        require(createdAtMillis > 0)
        require(deadlineAtMillis == null || deadlineAtMillis > createdAtMillis)
    }

    companion object {
        const val MAX_REQUEST_CHARS = 32_000
    }
}

enum class RequestSource { CHAT, VOICE, SHARE, SCHEDULE, LOCAL_API }

enum class ToolRisk { READ_ONLY, REVERSIBLE_WRITE, EXTERNAL_SIDE_EFFECT, CRITICAL }

data class TaskBudget(
    val maxSteps: Int = 12,
    val maxRetriesPerStep: Int = 1,
    val maxWallTimeMillis: Long = 120_000,
    val maxModelCalls: Int = 12,
    val maxOutputCharsPerStep: Int = 12_000
) {
    init {
        require(maxSteps in 1..100)
        require(maxRetriesPerStep in 0..5)
        require(maxWallTimeMillis in 1_000..3_600_000)
        require(maxModelCalls in 1..100)
        require(maxOutputCharsPerStep in 256..64_000)
    }
}

data class PlanStep(
    val id: String,
    val toolId: String,
    val argumentsJson: String,
    val dependsOn: Set<String> = emptySet(),
    val risk: ToolRisk,
    val purpose: String,
    val expectedOutcome: String,
    val timeoutMillis: Long = 30_000
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(toolId.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        require(argumentsJson.length <= 16_384)
        require(purpose.isNotBlank() && purpose.length <= 500)
        require(expectedOutcome.isNotBlank() && expectedOutcome.length <= 500)
        require(timeoutMillis in 100..300_000)
        require(id !in dependsOn)
    }
}

data class AgentPlan(
    val taskId: String,
    val revision: Long,
    val summary: String,
    val steps: List<PlanStep>,
    val budget: TaskBudget = TaskBudget()
) {
    init {
        require(taskId.isNotBlank())
        require(revision >= 0)
        require(summary.isNotBlank() && summary.length <= 2_000)
        require(steps.isNotEmpty() && steps.size <= budget.maxSteps)
        val ids = steps.map { it.id }.toSet()
        require(ids.size == steps.size) { "Step IDs must be unique" }
        require(steps.all { it.dependsOn.all(ids::contains) }) { "Unknown dependency" }
        require(isAcyclic(steps)) { "Plan dependency graph contains a cycle" }
    }

    private fun isAcyclic(steps: List<PlanStep>): Boolean {
        val byId = steps.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return false
            if (id in visited) return true
            visiting += id
            for (dependency in byId.getValue(id).dependsOn) {
                if (!visit(dependency)) return false
            }
            visiting -= id
            visited += id
            return true
        }
        return steps.all { visit(it.id) }
    }
}

enum class AgentEventType {
    REQUEST_ACCEPTED, PLAN_PROPOSED, PLAN_VALIDATED, APPROVAL_REQUESTED,
    APPROVAL_GRANTED, APPROVAL_DENIED, STEP_STARTED, STEP_COMPLETED,
    STEP_FAILED, STEP_RETRIED, TASK_PAUSED, TASK_RESUMED, TASK_CANCELLED,
    VERIFICATION_PASSED, VERIFICATION_FAILED, TASK_COMPLETED, MEMORY_PROPOSED
}

/** Append-only event envelope. Payload must be bounded and contain no credentials. */
data class AgentEvent(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val stepId: String? = null,
    val type: AgentEventType,
    val occurredAtMillis: Long,
    val payloadJson: String = "{}"
) {
    init {
        require(id.isNotBlank() && taskId.isNotBlank())
        require(occurredAtMillis > 0)
        require(payloadJson.length <= 8_192)
    }
}

data class VerificationResult(
    val passed: Boolean,
    val verifierId: String,
    val evidenceRefs: List<String>,
    val explanation: String
) {
    init {
        require(verifierId.isNotBlank())
        require(evidenceRefs.size <= 32 && evidenceRefs.all { it.isNotBlank() && it.length <= 256 })
        require(explanation.length <= 2_000)
    }
}

enum class MemoryKind { EPISODIC, SEMANTIC, PROCEDURAL, WORKING, PROJECT }

data class MemoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val kind: MemoryKind,
    val content: String,
    val sourceRef: String,
    val confidence: Float,
    val createdAtMillis: Long,
    val supersedesId: String? = null,
    val userCorrectable: Boolean = true
) {
    init {
        require(id.isNotBlank())
        require(content.isNotBlank() && content.length <= 8_000)
        require(sourceRef.isNotBlank() && sourceRef.length <= 512)
        require(confidence in 0f..1f)
        require(createdAtMillis > 0)
    }
}
