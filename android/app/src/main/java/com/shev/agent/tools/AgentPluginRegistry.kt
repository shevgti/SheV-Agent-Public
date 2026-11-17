package com.shev.agent.tools

/**
 * A statically bundled plugin contribution. This is intentionally not a script,
 * dynamic class loader, shell command, or remote executable package.
 */
data class AgentPluginContribution(
    val id: String,
    val version: String,
    val tools: List<AgentToolDescriptor>
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{1,31}")))
        require(version.matches(Regex("0|[1-9][0-9]*\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[A-Za-z0-9.-]+)?")))
        require(tools.isNotEmpty() && tools.size <= 64)
        require(tools.map { it.id }.distinct().size == tools.size)
        require(tools.all { it.id.startsWith("$id.") }) {
            "Plugin tools must be namespaced under the plugin ID"
        }
    }
}

enum class AgentPluginActivationResult {
    ACTIVATED, ALREADY_ACTIVE, USER_APPROVAL_REQUIRED, INVALID_CONTRIBUTION, TOOL_ID_CONFLICT
}

/**
 * Small in-process lifecycle manager inspired by reversible plugin effects.
 * Only app-bundled Kotlin contributions can be activated. User approval is
 * required for activation; deactivation removes descriptors immediately.
 */
class AgentPluginRegistry(private val toolRegistry: AgentToolRegistry) {
    private val activePlugins = linkedMapOf<String, List<String>>()

    fun activate(
        contribution: AgentPluginContribution,
        explicitlyApprovedByUser: Boolean
    ): AgentPluginActivationResult {
        if (!explicitlyApprovedByUser) return AgentPluginActivationResult.USER_APPROVAL_REQUIRED
        if (contribution.id in activePlugins) return AgentPluginActivationResult.ALREADY_ACTIVE
        if (contribution.tools.any { toolRegistry.find(it.id) != null }) {
            return AgentPluginActivationResult.TOOL_ID_CONFLICT
        }

        val registered = mutableListOf<String>()
        return try {
            contribution.tools.forEach { descriptor ->
                toolRegistry.register(descriptor)
                registered += descriptor.id
            }
            activePlugins[contribution.id] = registered.toList()
            AgentPluginActivationResult.ACTIVATED
        } catch (_: IllegalArgumentException) {
            registered.forEach(toolRegistry::unregister)
            AgentPluginActivationResult.TOOL_ID_CONFLICT
        }
    }

    fun deactivate(pluginId: String): Boolean {
        val toolIds = activePlugins.remove(pluginId) ?: return false
        toolIds.forEach(toolRegistry::unregister)
        return true
    }

    fun isActive(pluginId: String): Boolean = pluginId in activePlugins

    fun activePluginIds(): Set<String> = activePlugins.keys.toSet()
}
