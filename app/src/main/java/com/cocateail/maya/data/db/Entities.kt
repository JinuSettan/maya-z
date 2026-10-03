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
