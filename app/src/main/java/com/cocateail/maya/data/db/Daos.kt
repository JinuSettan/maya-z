package com.cocateail.maya.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessagesForConversationOnce(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND content LIKE '%' || :query || '%' ORDER BY createdAt ASC")
    fun searchMessagesForConversation(conversationId: String, query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchAllMessages(query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET pinned = :pinned WHERE id = :id")
    suspend fun updateMessagePinned(id: String, pinned: Boolean)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
}

@Dao
interface TessaTaskDao {
    @Query("SELECT * FROM tessa_tasks ORDER BY updatedAt DESC")
    fun observeAllTasks(): Flow<List<TessaTaskEntity>>

    @Query("SELECT * FROM tessa_tasks ORDER BY updatedAt DESC")
    suspend fun getAllTasksOnce(): List<TessaTaskEntity>

    @Query("SELECT * FROM tessa_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): TessaTaskEntity?

    @Query("SELECT * FROM tessa_tasks WHERE relatedProject = :projectName ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestTaskForProject(projectName: String): TessaTaskEntity?

    @Query("SELECT * FROM tessa_tasks WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_PERMISSION', 'PAUSED') ORDER BY updatedAt DESC")
    suspend fun getActiveTasksOnce(): List<TessaTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TessaTaskEntity)

    @Update
    suspend fun updateTask(task: TessaTaskEntity)

    @Query("DELETE FROM tessa_tasks WHERE taskId = :taskId")
    suspend fun deleteTaskById(taskId: String)
}

