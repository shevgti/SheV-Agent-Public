package com.shev.agent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Stores the user's validated declarative workspace layout as versioned JSON. */
@Entity(tableName = "workspace_layouts")
data class WorkspaceLayoutEntity(
    @PrimaryKey val layoutId: String = "default",
    val schemaVersion: Int = 1,
    val layoutJson: String,
    val updatedAtEpochMillis: Long
)
