package com.shev.agent.ui

/**
 * Declarative workspace layout model. The agent may propose layout changes,
 * but the renderer only accepts known component types and preserves safety controls.
 */
enum class WorkspaceComponentType {
    TASKS, PROJECTS, MEMORY, TOOLS, STATUS, QUICK_ACTIONS, PERMISSIONS, STOP_CONTROL
}

data class WorkspaceComponent(
    val id: String,
    val type: WorkspaceComponentType,
    val title: String,
    val visible: Boolean = true,
    val position: Int
)

data class AgentWorkspaceLayout(
    val version: Int = 1,
    val components: List<WorkspaceComponent>
)

sealed interface WorkspaceEdit {
    data class Add(val component: WorkspaceComponent) : WorkspaceEdit
    data class Move(val componentId: String, val newPosition: Int) : WorkspaceEdit
    data class SetVisibility(val componentId: String, val visible: Boolean) : WorkspaceEdit
    data class Rename(val componentId: String, val title: String) : WorkspaceEdit
    data class Remove(val componentId: String) : WorkspaceEdit
}

enum class WorkspaceEditStatus { APPLIED, REJECTED }

data class WorkspaceEditResult(
    val status: WorkspaceEditStatus,
    val layout: AgentWorkspaceLayout,
    val reason: String
)

object AgentWorkspaceLayoutPolicy {
    private const val MAX_COMPONENTS = 12
    private const val MAX_TITLE_LENGTH = 48
    private val protectedTypes = setOf(
        WorkspaceComponentType.PERMISSIONS,
        WorkspaceComponentType.STOP_CONTROL
    )

    fun validate(layout: AgentWorkspaceLayout): String? {
        if (layout.version != 1) return "Unsupported layout version"
        if (layout.components.size > MAX_COMPONENTS) return "Too many workspace components"
        if (layout.components.map { it.id }.toSet().size != layout.components.size) return "Duplicate component id"
        if (layout.components.any { it.id.isBlank() || it.id.length > 64 }) return "Invalid component id"
        if (layout.components.any { it.title.isBlank() || it.title.length > MAX_TITLE_LENGTH }) return "Invalid component title"
        if (layout.components.any { it.position < 0 || it.position >= MAX_COMPONENTS }) return "Invalid component position"
        if (layout.components.map { it.position }.toSet().size != layout.components.size) return "Duplicate component position"
        if (protectedTypes.any { type -> layout.components.none { it.type == type && it.visible } }) {
            return "Safety controls must remain visible"
        }
        return null
    }

    fun apply(layout: AgentWorkspaceLayout, edit: WorkspaceEdit): WorkspaceEditResult {
        val initialError = validate(layout)
        if (initialError != null) return rejected(layout, initialError)
        val changed = layout.components.toMutableList()
        when (edit) {
            is WorkspaceEdit.Add -> {
                if (changed.size >= MAX_COMPONENTS) return rejected(layout, "Workspace is full")
                if (changed.any { it.id == edit.component.id }) return rejected(layout, "Component already exists")
                if (edit.component.type in protectedTypes) return rejected(layout, "Safety controls are system-managed")
                changed.add(edit.component.copy(position = changed.size))
            }
            is WorkspaceEdit.Remove -> {
                val target = changed.find { it.id == edit.componentId } ?: return rejected(layout, "Component not found")
                if (target.type in protectedTypes) return rejected(layout, "Safety controls cannot be removed")
                changed.remove(target)
                normalize(changed)
            }
            is WorkspaceEdit.Move -> {
                val index = changed.indexOfFirst { it.id == edit.componentId }
                if (index < 0) return rejected(layout, "Component not found")
                if (edit.newPosition !in changed.indices) return rejected(layout, "Invalid target position")
                val item = changed.removeAt(index)
                changed.add(edit.newPosition.coerceAtMost(changed.size), item)
                normalize(changed)
            }
            is WorkspaceEdit.SetVisibility -> {
                val index = changed.indexOfFirst { it.id == edit.componentId }
                if (index < 0) return rejected(layout, "Component not found")
                if (!edit.visible && changed[index].type in protectedTypes) {
                    return rejected(layout, "Safety controls must remain visible")
                }
                changed[index] = changed[index].copy(visible = edit.visible)
            }
            is WorkspaceEdit.Rename -> {
                val index = changed.indexOfFirst { it.id == edit.componentId }
                if (index < 0) return rejected(layout, "Component not found")
                if (edit.title.isBlank() || edit.title.length > MAX_TITLE_LENGTH) return rejected(layout, "Invalid title")
                if (changed[index].type in protectedTypes) return rejected(layout, "Safety control labels are system-managed")
                changed[index] = changed[index].copy(title = edit.title)
            }
        }
        val result = AgentWorkspaceLayout(components = changed)
        val error = validate(result)
        return if (error == null) WorkspaceEditResult(WorkspaceEditStatus.APPLIED, result, "Layout updated")
        else rejected(layout, error)
    }

    private fun normalize(items: MutableList<WorkspaceComponent>) {
        for (index in items.indices) items[index] = items[index].copy(position = index)
    }

    private fun rejected(layout: AgentWorkspaceLayout, reason: String) =
        WorkspaceEditResult(WorkspaceEditStatus.REJECTED, layout, reason)
}
