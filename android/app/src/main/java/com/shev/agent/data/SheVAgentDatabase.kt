package com.shev.agent.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AgentTaskEntity::class, AgentEventEntity::class, ImprovementEntity::class, CapabilityGrantEntity::class, WorkspaceLayoutEntity::class, ConversationMessageEntity::class, ApprovalEvidenceEntity::class, ConversationStateEntity::class],
    version = 9,
    exportSchema = false
)
abstract class SheVAgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
}

private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS improvements (id TEXT NOT NULL PRIMARY KEY, summary TEXT NOT NULL, diffPreview TEXT NOT NULL, stage TEXT NOT NULL, diffReviewed INTEGER NOT NULL, testReportId TEXT, testResult TEXT, approvalGranted INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)")
    }
}

private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE improvements ADD COLUMN expectedHeadSha TEXT")
    }
}

private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS capability_grants (grantId TEXT NOT NULL PRIMARY KEY, capability TEXT NOT NULL, scopeType TEXT NOT NULL, scopeValue TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL, expiresAtEpochMillis INTEGER, revokedAtEpochMillis INTEGER)")
    }
}

private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS workspace_layouts (layoutId TEXT NOT NULL PRIMARY KEY, schemaVersion INTEGER NOT NULL, layoutJson TEXT NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)")
    }
}

private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_messages (id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_messages_createdAtEpochMillis ON conversation_messages (createdAtEpochMillis)")
    }
}

internal val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agent_tasks ADD COLUMN resumePhase TEXT")
        db.execSQL("ALTER TABLE agent_events ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agent_events ADD COLUMN idempotencyKey TEXT")
        db.execSQL("ALTER TABLE agent_events ADD COLUMN evidenceRef TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_events_idempotencyKey ON agent_events (idempotencyKey)")
    }
}

internal val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS approval_evidence (evidenceHash TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, expectedRevision INTEGER NOT NULL, operation TEXT NOT NULL, issuedAtEpochMillis INTEGER NOT NULL, expiresAtEpochMillis INTEGER NOT NULL, consumedAtEpochMillis INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_approval_evidence_taskId_expectedRevision ON approval_evidence (taskId, expectedRevision)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_approval_evidence_expiresAtEpochMillis ON approval_evidence (expiresAtEpochMillis)")
    }
}

internal val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_state (conversationId TEXT NOT NULL PRIMARY KEY, summaryCiphertext TEXT NOT NULL, summaryThroughMessageId TEXT, summaryVersion INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, status TEXT NOT NULL)")
    }
}

object SheVAgentDatabaseProvider {
    @Volatile
    private var instance: SheVAgentDatabase? = null

    fun get(context: android.content.Context): SheVAgentDatabase =
        instance ?: synchronized(this) {
            instance ?: androidx.room.Room.databaseBuilder(
                context.applicationContext,
                SheVAgentDatabase::class.java,
                "shev-agent.db"
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9
            ).build().also { instance = it }
        }
}
