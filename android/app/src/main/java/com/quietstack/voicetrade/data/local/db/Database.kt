package com.quietstack.voicetrade.data.local.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long?,
    val summary: String,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: Long,
    val messageId: String,
    val role: String,
    val text: String?,
    val cardJson: String?,
    val timestamp: Long,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Transaction
    suspend fun saveSession(session: SessionEntity, messages: List<MessageEntity>) {
        val id = insertSession(session)
        insertMessages(messages.map { it.copy(sessionId = id) })
    }

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY rowId ASC")
    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
}
