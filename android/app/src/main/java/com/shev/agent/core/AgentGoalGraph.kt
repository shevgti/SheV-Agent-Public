package com.shev.agent.core

data class AgentGoalBudget(
    val maxMilestones: Int = 12,
    val maxTasks: Int = 100,
    val maxTotalSteps: Int = 300,
    val maxStepsPerTask: Int = 20,
    val maxWallTimePerTaskMillis: Long = 180_000,
    val maxTotalWallTimeMillis: Long = 3_600_000,
    val maxModelCallsPerTask: Int = 12,
    val maxTotalModelCalls: Int = 100
) {
    init {
        require(maxMilestones in 1..50)
        require(maxTasks in 1..500)
        require(maxTotalSteps in 1..2_000)
        require(maxStepsPerTask in 1..100)
        require(maxWallTimePerTaskMillis in 1_000..3_600_000)
        require(maxTotalWallTimeMillis in maxWallTimePerTaskMillis..86_400_000)
        require(maxModelCallsPerTask in 1..100)
        require(maxTotalModelCalls in maxModelCallsPerTask..1_000)
    }
}

data class AgentGoalMilestone(
    val id: String,
    val title: String,
    val dependsOnMilestoneIds: Set<String> = emptySet()
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(title.isNotBlank() && title.length <= 300)
        require(id !in dependsOnMilestoneIds)
    }
}

data class AgentGoalTask(
    val id: String,
    val milestoneId: String,
    val dependsOnTaskIds: Set<String> = emptySet(),
    val budget: TaskBudget = TaskBudget()
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(milestoneId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(id !in dependsOnTaskIds)
    }
}

/** Goal → milestone → task graph. Execution remains delegated to the kernel task ledger. */
data class AgentGoalGraph(
    val goalId: String,
    val title: String,
    val milestones: List<AgentGoalMilestone>,
    val tasks: List<AgentGoalTask>,
    val budget: AgentGoalBudget = AgentGoalBudget()
) {
    init {
        require(goalId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(title.isNotBlank() && title.length <= 500)
    }
}

enum class AgentGoalGraphFailure {
    EMPTY_GRAPH,
    GRAPH_LIMIT_EXCEEDED,
    DUPLICATE_ID,
    UNKNOWN_MILESTONE_DEPENDENCY,
    UNKNOWN_TASK_MILESTONE,
    UNKNOWN_TASK_DEPENDENCY,
    MILESTONE_CYCLE,
    TASK_CYCLE,
    CROSS_MILESTONE_DEPENDENCY,
    TASK_BUDGET_EXCEEDED,
    GOAL_BUDGET_EXCEEDED
}

data class AgentGoalGraphValidation(
    val accepted: Boolean,
    val failure: AgentGoalGraphFailure? = null,
    val nodeId: String? = null
)

/** Validates the full hierarchy before any child task is persisted or scheduled. */
object AgentGoalGraphValidator {
    fun validate(graph: AgentGoalGraph): AgentGoalGraphValidation {
        if (graph.milestones.isEmpty() || graph.tasks.isEmpty()) {
            return AgentGoalGraphValidation(false, AgentGoalGraphFailure.EMPTY_GRAPH)
        }
        if (graph.milestones.size > graph.budget.maxMilestones ||
            graph.tasks.size > graph.budget.maxTasks
        ) return AgentGoalGraphValidation(false, AgentGoalGraphFailure.GRAPH_LIMIT_EXCEEDED)

        val milestoneIds = graph.milestones.map { it.id }
        val taskIds = graph.tasks.map { it.id }
        if (milestoneIds.distinct().size != milestoneIds.size ||
            taskIds.distinct().size != taskIds.size
        ) return AgentGoalGraphValidation(false, AgentGoalGraphFailure.DUPLICATE_ID)

        val milestones = graph.milestones.associateBy { it.id }
        val tasks = graph.tasks.associateBy { it.id }
        for (milestone in graph.milestones) {
            if (milestone.dependsOnMilestoneIds.any { it !in milestones }) {
                return AgentGoalGraphValidation(
                    false, AgentGoalGraphFailure.UNKNOWN_MILESTONE_DEPENDENCY, milestone.id
                )
            }
        }
        if (hasCycle(milestoneIds, milestones.mapValues { it.value.dependsOnMilestoneIds })) {
            return AgentGoalGraphValidation(false, AgentGoalGraphFailure.MILESTONE_CYCLE)
        }

        for (task in graph.tasks) {
            if (task.milestoneId !in milestones) {
                return AgentGoalGraphValidation(false, AgentGoalGraphFailure.UNKNOWN_TASK_MILESTONE, task.id)
            }
            if (task.dependsOnTaskIds.any { it !in tasks }) {
                return AgentGoalGraphValidation(false, AgentGoalGraphFailure.UNKNOWN_TASK_DEPENDENCY, task.id)
            }
        }
        if (hasCycle(taskIds, tasks.mapValues { it.value.dependsOnTaskIds })) {
            return AgentGoalGraphValidation(false, AgentGoalGraphFailure.TASK_CYCLE)
        }

        for (task in graph.tasks) {
            val allowedMilestones = milestoneAncestors(task.milestoneId, milestones)
            for (dependencyId in task.dependsOnTaskIds) {
                val dependencyMilestone = tasks.getValue(dependencyId).milestoneId
                if (dependencyMilestone !in allowedMilestones) {
                    return AgentGoalGraphValidation(
                        false, AgentGoalGraphFailure.CROSS_MILESTONE_DEPENDENCY, task.id
                    )
                }
            }
            if (task.budget.maxSteps > graph.budget.maxStepsPerTask ||
                task.budget.maxWallTimeMillis > graph.budget.maxWallTimePerTaskMillis ||
                task.budget.maxModelCalls > graph.budget.maxModelCallsPerTask
            ) return AgentGoalGraphValidation(
                false, AgentGoalGraphFailure.TASK_BUDGET_EXCEEDED, task.id
            )
        }

        val totalSteps = graph.tasks.sumOf { it.budget.maxSteps }
        val totalWallTime = graph.tasks.sumOf { it.budget.maxWallTimeMillis }
        val totalModelCalls = graph.tasks.sumOf { it.budget.maxModelCalls }
        if (totalSteps > graph.budget.maxTotalSteps ||
            totalWallTime > graph.budget.maxTotalWallTimeMillis ||
            totalModelCalls > graph.budget.maxTotalModelCalls
        ) return AgentGoalGraphValidation(false, AgentGoalGraphFailure.GOAL_BUDGET_EXCEEDED)

        return AgentGoalGraphValidation(true)
    }

    private fun milestoneAncestors(
        milestoneId: String,
        milestones: Map<String, AgentGoalMilestone>
    ): Set<String> {
        val visited = linkedSetOf<String>()
        fun visit(id: String) {
            if (!visited.add(id)) return
            milestones[id]?.dependsOnMilestoneIds?.forEach(::visit)
        }
        visit(milestoneId)
        return visited
    }

    private fun hasCycle(
        ids: List<String>,
        dependencies: Map<String, Set<String>>
    ): Boolean {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            for (dependency in dependencies[id].orEmpty()) {
                if (visit(dependency)) return true
            }
            visiting -= id
            visited += id
            return false
        }
        return ids.any(::visit)
    }
}
