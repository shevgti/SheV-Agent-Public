package com.shev.chat.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "chat_entries",
    indices = [Index(value = ["projectId", "createdAtEpochMillis"])]
)
data class ChatEntryEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long
)

@Dao
interface ChatEntryDao {
    @Query(
        "SELECT * FROM chat_entries WHERE projectId = :projectId " +
            "ORDER BY createdAtEpochMillis DESC, id DESC LIMIT 100"
    )
    fun observeRecent(projectId: String): Flow<List<ChatEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ChatEntryEntity)

    @Query(
        "DELETE FROM chat_entries WHERE projectId = :projectId AND id NOT IN (" +
            "SELECT id FROM chat_entries WHERE projectId = :projectId " +
            "ORDER BY createdAtEpochMillis DESC, id DESC LIMIT :keep)"
    )
    suspend fun pruneToRecent(projectId: String, keep: Int)

    @Transaction
    suspend fun insertAndPrune(entry: ChatEntryEntity, keep: Int = 100) {
        require(keep > 0)
        insert(entry)
        pruneToRecent(entry.projectId, keep)
    }

    @Query("DELETE FROM chat_entries WHERE projectId = :projectId")
    suspend fun deleteProjectHistory(projectId: String)

    @Query("SELECT COUNT(*) FROM chat_entries WHERE projectId = :projectId")
    suspend fun countForProject(projectId: String): Int
}

@Database(
    entities = [ChatEntryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatHistoryDatabase : RoomDatabase() {
    abstract fun entries(): ChatEntryDao

    companion object {
        @Volatile private var INSTANCE: ChatHistoryDatabase? = null

        fun get(context: Context): ChatHistoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatHistoryDatabase::class.java,
                    "shev-chat-history.db"
                ).build().also { INSTANCE = it }
            }
    }
}
