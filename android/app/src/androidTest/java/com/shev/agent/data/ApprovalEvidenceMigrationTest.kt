package com.shev.agent.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ApprovalEvidenceMigrationTest {

    @Test
    fun migration7To8AddsApprovalEvidenceAndPreservesExistingTaskAndEventRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "approval-migration-${UUID.randomUUID()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Recreate the relevant Room v7 tables, including the revision/idempotency
                    // columns introduced by migration 6->7, and seed real pre-migration rows.
                    db.execSQL(
                        "CREATE TABLE agent_tasks (" +
                            "id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, phase TEXT NOT NULL, " +
                            "requiresApproval INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL, " +
                            "updatedAtEpochMillis INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0, " +
                            "resumePhase TEXT)"
                    )
                    db.execSQL(
                        "CREATE TABLE agent_events (" +
                            "id TEXT NOT NULL PRIMARY KEY, taskId TEXT, eventType TEXT NOT NULL, " +
                            "message TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL, " +
                            "revision INTEGER NOT NULL DEFAULT 0, idempotencyKey TEXT, evidenceRef TEXT)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX index_agent_events_idempotencyKey " +
                            "ON agent_events (idempotencyKey)"
                    )
                    db.execSQL(
                        "INSERT INTO agent_tasks " +
                            "(id, title, phase, requiresApproval, createdAtEpochMillis, updatedAtEpochMillis, revision, resumePhase) " +
                            "VALUES ('task-preserved', 'Существующее поручение', 'WAITING_APPROVAL', 1, 100, 200, 3, NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO agent_events " +
                            "(id, taskId, eventType, message, createdAtEpochMillis, revision, idempotencyKey, evidenceRef) " +
                            "VALUES ('event-preserved', 'task-preserved', 'REQUEST_START', 'PLANNING->WAITING_APPROVAL', 200, 3, 'legacy-key', NULL)"
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)

        try {
            val db = helper.writableDatabase
            MIGRATION_7_8.migrate(db)

            val columns = db.query("PRAGMA table_info(approval_evidence)").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
            }
            assertEquals(
                setOf(
                    "evidenceHash",
                    "taskId",
                    "expectedRevision",
                    "operation",
                    "issuedAtEpochMillis",
                    "expiresAtEpochMillis",
                    "consumedAtEpochMillis"
                ),
                columns
            )

            val indexes = db.query("PRAGMA index_list('approval_evidence')").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
            }
            assertTrue(
                "task/revision lookup index is missing",
                indexes.contains("index_approval_evidence_taskId_expectedRevision")
            )
            assertTrue(
                "expiry cleanup index is missing",
                indexes.contains("index_approval_evidence_expiresAtEpochMillis")
            )

            db.query(
                "SELECT id, title, phase, requiresApproval, revision FROM agent_tasks WHERE id = 'task-preserved'"
            ).use { cursor ->
                assertTrue("pre-existing task row was lost", cursor.moveToFirst())
                assertEquals("task-preserved", cursor.getString(0))
                assertEquals("Существующее поручение", cursor.getString(1))
                assertEquals("WAITING_APPROVAL", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals(3L, cursor.getLong(4))
            }
            db.query(
                "SELECT id, taskId, eventType, idempotencyKey FROM agent_events WHERE id = 'event-preserved'"
            ).use { cursor ->
                assertTrue("pre-existing event row was lost", cursor.moveToFirst())
                assertEquals("event-preserved", cursor.getString(0))
                assertEquals("task-preserved", cursor.getString(1))
                assertEquals("REQUEST_START", cursor.getString(2))
                assertEquals("legacy-key", cursor.getString(3))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
}
