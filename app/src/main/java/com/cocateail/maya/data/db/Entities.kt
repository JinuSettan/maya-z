package com.cocateail.maya.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String?
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val createdAt: Long,
    val pinned: Boolean = false,
    val replyToId: String? = null,
    val imageUrl: String? = null,
    val repliedMessageSender: String? = null,
    val repliedMessageText: String? = null,
    val repliedMessageImageUrl: String? = null
)

@Entity(tableName = "tessa_tasks")
data class TessaTaskEntity(
    @PrimaryKey val taskId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val userRequest: String,
    val taskType: String, // CHAT, INFORMATION, TASK, ACTION
    val status: String, // QUEUED, WAITING_FOR_PERMISSION, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    val currentStep: Int = 0,
    val totalSteps: Int = 1,
    val requiresPermission: Boolean = false,
    val permissionState: String = "NOT_REQUIRED", // NOT_REQUIRED, PENDING_USER_APPROVAL, GRANTED_ONCE, GRANTED_REUSABLE_RULE, DENIED, BLOCKED_PROHIBITED_ACTION
    val requiredPermissionsCsv: String = "",
    val targetTool: String = "",
    val result: String? = null,
    val error: String? = null,
    val relatedProject: String? = null,
    val relatedMessage: String? = null,
    val relatedFile: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val timeoutMs: Long = 120_000L,
    val revisionHistoryJson: String = "[]",
    val previousStateSnapshotJson: String? = null
)

