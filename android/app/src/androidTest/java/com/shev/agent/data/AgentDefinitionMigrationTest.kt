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
class AgentDefinitionMigrationTest {
    @Test
    fun migrationsNineToElevenCreateTableAndPreserveDraftWithMarkdownDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "agent-definition-migration-${UUID.randomUUID()}.db"
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            val db = helper.writableDatabase
            MIGRATION_9_10.migrate(db)
            db.execSQL(
                "INSERT INTO agent_definitions VALUES " +
                    "('legacy-agent', 'Старый агент', 'Сохранить паспорт', '{}', 'DRAFT', 1, 10, 20)"
            )
            MIGRATION_10_11.migrate(db)
            db.query(
                "SELECT agentId, name, purpose, agentMarkdown FROM agent_definitions WHERE agentId='legacy-agent'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-agent", cursor.getString(0))
                assertEquals("Старый агент", cursor.getString(1))
                assertEquals("Сохранить паспорт", cursor.getString(2))
                assertEquals("", cursor.getString(3))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
}
