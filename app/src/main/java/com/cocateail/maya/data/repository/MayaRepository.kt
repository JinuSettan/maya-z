package com.cocateail.maya.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.cocateail.maya.data.api.GeminiService
import com.cocateail.maya.data.db.ConversationDao
import com.cocateail.maya.data.db.ConversationEntity
import com.cocateail.maya.data.db.MessageDao
import com.cocateail.maya.data.db.MessageEntity
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.MayaCharacter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

class MayaRepository(
    private val context: Context,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val prefs = context.getSharedPreferences("maya_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        GeminiService.initLocalGgufProvider(context)
    }

    val conversations: Flow<List<ConversationEntity>> = conversationDao.getAllConversations()

    private val _selectedCharacter = MutableStateFlow(
        try {
            MayaCharacter.valueOf(prefs.getString("character", "MAYA") ?: "MAYA")
        } catch (e: Exception) {
            MayaCharacter.MAYA
        }
    )
    val selectedCharacter: StateFlow<MayaCharacter> = _selectedCharacter.asStateFlow()

    private val _developerConfig = MutableStateFlow(loadDeveloperConfig())
    val developerConfig: StateFlow<DeveloperConfig> = _developerConfig.asStateFlow()

    private val _developerUnlocked = MutableStateFlow(prefs.getBoolean("dev_unlocked", false))
    val developerUnlocked: StateFlow<Boolean> = _developerUnlocked.asStateFlow()

    fun setCharacter(character: MayaCharacter) {
        _selectedCharacter.value = character
        prefs.edit().putString("character", character.name).apply()
    }

    fun saveDeveloperConfig(config: DeveloperConfig) {
        _developerConfig.value = config
        prefs.edit().putString("dev_config", json.encodeToString(DeveloperConfig.serializer(), config)).apply()
    }

    private fun loadDeveloperConfig(): DeveloperConfig {
        val raw = prefs.getString("dev_config", null) ?: return DeveloperConfig()
        return try {
            json.decodeFromString(DeveloperConfig.serializer(), raw)
        } catch (e: Exception) {
            DeveloperConfig()
        }
    }

    fun unlockDeveloperMode(): Boolean {
        _developerUnlocked.value = true
        prefs.edit().putBoolean("dev_unlocked", true).apply()
        return true
    }

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    fun searchMessages(conversationId: String, query: String): Flow<List<MessageEntity>> {
        return messageDao.searchMessagesForConversation(conversationId, query)
    }

    fun searchAllMessages(query: String): Flow<List<MessageEntity>> {
        return messageDao.searchAllMessages(query)
    }

    suspend fun createConversation(title: String = "New conversation"): ConversationEntity {
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = "conv-" + UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            preview = null
        )
        conversationDao.insertConversation(conversation)
        return conversation
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteConversationById(id)
        messageDao.deleteMessagesForConversation(id)
    }

    suspend fun togglePinMessage(messageId: String, currentPinned: Boolean) {
        messageDao.updateMessagePinned(messageId, !currentPinned)
    }

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        imageBitmap: Bitmap? = null,
        imageUrl: String? = null,
        replyToId: String? = null,
        repliedMessageSender: String? = null,
        repliedMessageText: String? = null,
        repliedMessageImageUrl: String? = null
    ): MessageEntity {
        val now = System.currentTimeMillis()
        val userMsg = MessageEntity(
            id = "msg-user-" + UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "user",
            content = content,
            createdAt = now,
            imageUrl = imageUrl,
            replyToId = replyToId,
            repliedMessageSender = repliedMessageSender,
            repliedMessageText = repliedMessageText,
            repliedMessageImageUrl = repliedMessageImageUrl
        )
        messageDao.insertMessage(userMsg)

        // Check if user entered unlock code
        if (content.trim() == "/developerceo/maya/unlockcode/trainer") {
            unlockDeveloperMode()
        }

        // Fetch recent messages for context
        val existingMessages = messageDao.getMessagesForConversationOnce(conversationId)
        val history = existingMessages.takeLast(10).map { it.role to it.content }

        // Generate response
        val replyText = GeminiService.generateResponse(
            prompt = content,
            history = history,
            character = _selectedCharacter.value,
            config = _developerConfig.value,
            imageBitmap = imageBitmap,
            replyToId = replyToId,
            repliedMessageSender = repliedMessageSender,
            repliedMessageText = repliedMessageText,
            repliedMessageImageUrl = repliedMessageImageUrl
        )

        // Extract image/video URL from markdown if present e.g. ![AI Media](https://...)
        var extractedImageUrl: String? = null
        var cleanReplyText = replyText

        val imageRegex = Regex("!\\[.*?\\]\\((https?://[^\\s\\)]+)\\)")
        val match = imageRegex.find(replyText)
        if (match != null) {
            extractedImageUrl = match.groupValues[1]
            cleanReplyText = replyText.replace(match.value, "").trim()
        }

        val assistantMsg = MessageEntity(
            id = "msg-assistant-" + UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "assistant",
            content = if (cleanReplyText.isNotBlank()) cleanReplyText else replyText,
            createdAt = System.currentTimeMillis(),
            imageUrl = extractedImageUrl,
            replyToId = userMsg.id
        )
        messageDao.insertMessage(assistantMsg)

        // Update conversation preview and updatedAt
        val existingConv = conversationDao.getConversationById(conversationId)
        if (existingConv != null) {
            val title = if (existingConv.preview == null && content.isNotBlank()) {
                if (content.length > 25) content.take(25) + "..." else content
            } else {
                existingConv.title
            }
            val updatedConv = existingConv.copy(
                title = title,
                preview = replyText.take(100),
                updatedAt = System.currentTimeMillis()
            )
            conversationDao.updateConversation(updatedConv)
        }

        return assistantMsg
    }

    suspend fun insertMessageDirect(message: MessageEntity) {
        messageDao.insertMessage(message)
    }

    suspend fun updateMessageContent(messageId: String, newContent: String) {
        val existing = messageDao.getMessageById(messageId)
        if (existing != null) {
            messageDao.insertMessage(existing.copy(content = newContent))
        }
    }
}
