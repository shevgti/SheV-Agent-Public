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
    fun migration10To12CreatesPlanTablesAndDefaultsLegacyPlanRevision() {
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

            val tables = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertTrue(tables.contains("agent_plans"))
            assertTrue(tables.contains("agent_plan_steps"))

            db.query(
                "SELECT planRevision FROM mcp_invocation_authorizations WHERE invocationId = 'invocation-00000001'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
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
