package com.shev.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceLayoutPolicyTest {
    private fun layout() = AgentWorkspaceLayout(components = listOf(
        WorkspaceComponent("tasks", WorkspaceComponentType.TASKS, "Задачи", position = 0),
        WorkspaceComponent("memory", WorkspaceComponentType.MEMORY, "Память", position = 1),
        WorkspaceComponent("permissions", WorkspaceComponentType.PERMISSIONS, "Разрешения", position = 2),
        WorkspaceComponent("stop", WorkspaceComponentType.STOP_CONTROL, "Стоп", position = 3)
    ))

    @Test fun agentCanAddRenameMoveAndHideOrdinaryComponents() {
        val initial = layout()
        val added = AgentWorkspaceLayoutPolicy.apply(initial, WorkspaceEdit.Add(
            WorkspaceComponent("projects", WorkspaceComponentType.PROJECTS, "Проекты", position = 4)
        ))
        assertEquals(WorkspaceEditStatus.APPLIED, added.status)
        val renamed = AgentWorkspaceLayoutPolicy.apply(added.layout, WorkspaceEdit.Rename("projects", "Мои проекты"))
        assertEquals("Мои проекты", renamed.layout.components.first { it.id == "projects" }.title)
        val moved = AgentWorkspaceLayoutPolicy.apply(renamed.layout, WorkspaceEdit.Move("projects", 0))
        assertEquals("projects", moved.layout.components.minBy { it.position }.id)
        val movedToEnd = AgentWorkspaceLayoutPolicy.apply(moved.layout, WorkspaceEdit.Move("projects", 4))
        assertEquals(WorkspaceEditStatus.APPLIED, movedToEnd.status)
        assertEquals("projects", movedToEnd.layout.components.maxBy { it.position }.id)
        val hidden = AgentWorkspaceLayoutPolicy.apply(moved.layout, WorkspaceEdit.SetVisibility("memory", false))
        assertEquals(false, hidden.layout.components.first { it.id == "memory" }.visible)
    }

    @Test fun agentCannotHideOrRemoveSafetyControls() {
        val initial = layout()
        val hideStop = AgentWorkspaceLayoutPolicy.apply(initial, WorkspaceEdit.SetVisibility("stop", false))
        val removePermissions = AgentWorkspaceLayoutPolicy.apply(initial, WorkspaceEdit.Remove("permissions"))
        assertEquals(WorkspaceEditStatus.REJECTED, hideStop.status)
        assertEquals(WorkspaceEditStatus.REJECTED, removePermissions.status)
        assertEquals(initial, hideStop.layout)
        assertEquals(initial, removePermissions.layout)
    }

    @Test fun agentCannotAddSystemManagedSafetyControl() {
        val result = AgentWorkspaceLayoutPolicy.apply(layout(), WorkspaceEdit.Add(
            WorkspaceComponent("another-stop", WorkspaceComponentType.STOP_CONTROL, "Стоп 2", position = 4)
        ))
        assertEquals(WorkspaceEditStatus.REJECTED, result.status)
    }

    @Test fun invalidDuplicateIdsAndOversizedTitlesAreRejected() {
        val duplicate = AgentWorkspaceLayout(components = layout().components + layout().components.first().copy(position = 4))
        assertNotEquals(null, AgentWorkspaceLayoutPolicy.validate(duplicate))
        val result = AgentWorkspaceLayoutPolicy.apply(layout(), WorkspaceEdit.Rename("tasks", "x".repeat(49)))
        assertEquals(WorkspaceEditStatus.REJECTED, result.status)
    }

    @Test fun failedEditNeverMutatesOriginalLayout() {
        val initial = layout()
        val result = AgentWorkspaceLayoutPolicy.apply(initial, WorkspaceEdit.Remove("missing"))
        assertEquals(WorkspaceEditStatus.REJECTED, result.status)
        assertTrue(result.layout === initial)
    }
}
