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
class AgentPlanMigrationTest {
    @Test
    fun migration10To16CreatesPlanTablesMcpApprovalsAndStepExecution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "agent-plan-migration-${UUID.randomUUID()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE mcp_invocation_authorizations (" +
                            "nonceDigest TEXT NOT NULL PRIMARY KEY, invocationId TEXT NOT NULL, " +
                            "taskId TEXT NOT NULL, taskRevision INTEGER NOT NULL, stepId TEXT NOT NULL, " +
                            "stepRevision INTEGER NOT NULL, profileId TEXT NOT NULL, endpoint TEXT NOT NULL, " +
                            "nativeToolId TEXT NOT NULL, remoteToolName TEXT NOT NULL, argumentsSha256 TEXT NOT NULL, " +
                            "issuedAtEpochMillis INTEGER NOT NULL, expiresAtEpochMillis INTEGER NOT NULL, consumedAtEpochMillis INTEGER)"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX index_mcp_invocation_authorizations_invocationId " +
                            "ON mcp_invocation_authorizations (invocationId)"
                    )
                    db.execSQL(
                        "INSERT INTO mcp_invocation_authorizations " +
                            "(nonceDigest, invocationId, taskId, taskRevision, stepId, stepRevision, profileId, endpoint, nativeToolId, remoteToolName, argumentsSha256, issuedAtEpochMillis, expiresAtEpochMillis, consumedAtEpochMillis) " +
                            "VALUES ('digest', 'invocation-00000001', 'task-1', 2, 'step-1', 0, 'profile', 'https://example.org/mcp', 'profile.search', 'search', '${"a".repeat(64)}', 100, 200, NULL)"
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
            MIGRATION_10_11.migrate(db)
            MIGRATION_11_12.migrate(db)
            val plainPlan = """{"taskId":"task-1","revision":0}"""
            val plainStep = """{"id":"step-1","argumentsJson":"{}"}"""
            db.execSQL(
                "INSERT INTO agent_plans (taskId, planRevision, canonicalPlanJson, savedAtEpochMillis) VALUES (?, 0, ?, 100)",
                arrayOf("task-1", plainPlan)
            )
            db.execSQL(
                "INSERT INTO agent_plan_steps (taskId, stepId, planRevision, stepRevision, canonicalStepJson) VALUES (?, ?, 0, 0, ?)",
                arrayOf("task-1", "step-1", plainStep)
            )
            MIGRATION_12_13.migrate(db)
            MIGRATION_13_14.migrate(db)
            MIGRATION_14_15.migrate(db)
            MIGRATION_15_16.migrate(db)

            val tables = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertTrue(tables.contains("agent_plans"))
            assertTrue(tables.contains("agent_plan_steps"))
            assertTrue(tables.contains("mcp_invocation_approval_requests"))
            assertTrue(tables.contains("agent_plan_step_executions"))

            db.query(
                "SELECT planRevision, toolDefinitionSha256 FROM mcp_invocation_authorizations WHERE invocationId = 'invocation-00000001'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
                assertEquals("0".repeat(64), cursor.getString(1))
            }

            db.query("SELECT canonicalPlanJson FROM agent_plans WHERE taskId = 'task-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val encrypted = cursor.getString(0)
                assertTrue(encrypted.startsWith("enc:v1:"))
                assertEquals(plainPlan, LocalPlanCipher.decrypt(encrypted))
            }
            db.query("SELECT canonicalStepJson FROM agent_plan_steps WHERE taskId = 'task-1' AND stepId = 'step-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val encrypted = cursor.getString(0)
                assertTrue(encrypted.startsWith("enc:v1:"))
                assertEquals(plainStep, LocalPlanCipher.decrypt(encrypted))
            }

            val stepColumns = db.query("PRAGMA table_info(agent_plan_steps)").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            assertEquals(
                setOf("taskId", "stepId", "planRevision", "stepRevision", "canonicalStepJson"),
                stepColumns
            )
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
}
