package com.cocateail.maya.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.cocateail.maya.data.api.GeminiService
import com.cocateail.maya.data.db.ConversationDao
import com.cocateail.maya.data.db.ConversationEntity
import com.cocateail.maya.data.db.MessageDao
import com.cocateail.maya.data.db.MessageEntity
import com.cocateail.maya.data.db.TessaTaskDao
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.tessa.AssistantEngineMode
import com.cocateail.maya.tessa.DefaultMayaOfflineProvider
import com.cocateail.maya.tessa.DefaultMayaOnlineProvider
import com.cocateail.maya.tessa.TessaAgent
import com.cocateail.maya.tessa.TessaHttpModelProvider
import com.cocateail.maya.tessa.TessaModelRouter
import com.cocateail.maya.tessa.TessaTask
import com.cocateail.maya.tessa.TessaTaskStore
import com.cocateail.maya.tessa.TessaUiStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

class MayaRepository(
    private val context: Context? = null,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val tessaTaskDao: TessaTaskDao? = null,
    private val customPrefs: android.content.SharedPreferences? = null,
    private val customOfflineProvider: com.cocateail.maya.tessa.MayaOfflineProvider? = null,
    private val customOnlineProvider: com.cocateail.maya.tessa.MayaOnlineProvider? = null,
    private val customTessaModelProvider: com.cocateail.maya.tessa.TessaModelProvider? = null
) {
    companion object {
        const val DEVELOPER_UNLOCK_COMMAND = "/developerceo/maya/unlockcode/trainer"
        const val KEY_DEV_UNLOCKED = "dev_unlocked"
        const val KEY_DEV_CONFIG = "dev_config"
        const val KEY_CHARACTER = "character"

        fun isDeveloperUnlockCommand(input: String): Boolean {
            return input.trim() == DEVELOPER_UNLOCK_COMMAND
        }
    }

    private val prefs: android.content.SharedPreferences? =
        customPrefs ?: context?.getSharedPreferences("maya_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val gmailConnector = com.cocateail.maya.tessa.gmail.RealGmailConnector(
        context = context,
        configProvider = { developerConfig.value },
        onConfigUpdated = { saveDeveloperConfig(it) }
    )

    val tessaTaskStore = TessaTaskStore(tessaTaskDao)
    val tessaAgent = TessaAgent(
        context = context,
        modelProvider = customTessaModelProvider ?: TessaHttpModelProvider.sharedInstance,
        taskStore = tessaTaskStore,
        gmailConnector = gmailConnector
    )
    val modeRouter = TessaModelRouter(
        mayaOfflineProvider = customOfflineProvider ?: DefaultMayaOfflineProvider(context),
        mayaOnlineProvider = customOnlineProvider ?: DefaultMayaOnlineProvider(),
        tessaModelProvider = tessaAgent.modelProvider
    )

    val tessaTasks: Flow<List<TessaTask>> = tessaTaskStore.observeTasks()
    val tessaUiStatus: StateFlow<TessaUiStatus> = tessaAgent.uiStatus

    init {
        if (context != null) {
            GeminiService.initLocalGgufProvider(context)
        }
    }

    val conversations: Flow<List<ConversationEntity>> = conversationDao.getAllConversations()

    private val _selectedCharacter = MutableStateFlow(
        try {
            MayaCharacter.valueOf(prefs?.getString(KEY_CHARACTER, "MAYA") ?: "MAYA")
        } catch (e: Exception) {
            MayaCharacter.MAYA
        }
    )
    val selectedCharacter: StateFlow<MayaCharacter> = _selectedCharacter.asStateFlow()

    private val _developerConfig = MutableStateFlow(loadDeveloperConfig())
    val developerConfig: StateFlow<DeveloperConfig> = _developerConfig.asStateFlow()

    private val _developerUnlocked = MutableStateFlow(loadDeveloperUnlocked())
    val developerUnlocked: StateFlow<Boolean> = _developerUnlocked.asStateFlow()

    private fun loadDeveloperUnlocked(): Boolean {
        val flagUnlocked = prefs?.getBoolean(KEY_DEV_UNLOCKED, false) ?: false
        return flagUnlocked || _developerConfig.value.developerUnlocked
    }

    fun isUnlockCommand(input: String): Boolean = isDeveloperUnlockCommand(input)

    fun setCharacter(character: MayaCharacter) {
        _selectedCharacter.value = character
        prefs?.edit()?.putString(KEY_CHARACTER, character.name)?.commit()
    }

    private fun sanitizeTessaConfig(config: DeveloperConfig, unlockedOverride: Boolean? = null): DeveloperConfig {
        val rawModel = config.tessaModelId.trim()
        val cleanModelId = if (rawModel.isBlank() || DeveloperConfig.isObsoleteGeminiModel(rawModel)) {
            DeveloperConfig.DEFAULT_TESSA_MODEL_ID
        } else {
            rawModel
        }
        val cleanBaseUrl = config.tessaApiBaseUrl.trim().ifBlank { DeveloperConfig.DEFAULT_TESSA_BASE_URL }
        val cleanApiKey = if (!DeveloperConfig.isPlaceholderKey(config.tessaApiKey)) {
            config.tessaApiKey.trim()
        } else {
            DeveloperConfig.DEFAULT_TESSA_API_KEY
        }
        return config.copy(
            developerUnlocked = unlockedOverride ?: config.developerUnlocked,
            tessaModelId = cleanModelId,
            tessaApiBaseUrl = cleanBaseUrl,
            tessaApiKey = cleanApiKey
        )
    }

    fun saveDeveloperConfig(config: DeveloperConfig) {
        val effectiveConfig = sanitizeTessaConfig(
            config = config,
            unlockedOverride = if (_developerUnlocked.value) true else config.developerUnlocked
        )
        _developerConfig.value = effectiveConfig
        if (effectiveConfig.developerUnlocked) {
            _developerUnlocked.value = true
        }
        prefs?.edit()
            ?.putBoolean(KEY_DEV_UNLOCKED, _developerUnlocked.value)
            ?.putString(KEY_DEV_CONFIG, json.encodeToString(DeveloperConfig.serializer(), effectiveConfig))
            ?.commit()
    }

    private fun loadDeveloperConfig(): DeveloperConfig {
        val raw = prefs?.getString(KEY_DEV_CONFIG, null)
        val flagUnlocked = prefs?.getBoolean(KEY_DEV_UNLOCKED, false) ?: false
        if (raw == null) {
            return DeveloperConfig(developerUnlocked = flagUnlocked)
        }
        return try {
            val decoded = json.decodeFromString(DeveloperConfig.serializer(), raw)
            val upgraded = sanitizeTessaConfig(
                config = decoded,
                unlockedOverride = if (flagUnlocked) true else decoded.developerUnlocked
            )
            if (decoded != upgraded) {
                prefs?.edit()
                    ?.putString(KEY_DEV_CONFIG, json.encodeToString(DeveloperConfig.serializer(), upgraded))
                    ?.apply()
            }
            upgraded
        } catch (e: Exception) {
            DeveloperConfig(developerUnlocked = flagUnlocked)
        }
    }

    fun unlockDeveloperMode(): Boolean {
        _developerUnlocked.value = true
        val updatedConfig = _developerConfig.value.copy(developerUnlocked = true)
        _developerConfig.value = updatedConfig
        prefs?.edit()
            ?.putBoolean(KEY_DEV_UNLOCKED, true)
            ?.putString(KEY_DEV_CONFIG, json.encodeToString(DeveloperConfig.serializer(), updatedConfig))
            ?.commit()
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

        // Intercept exact developer unlock command BEFORE sending anything to Gemini/Qwen/Tessa
        if (isDeveloperUnlockCommand(content)) {
            unlockDeveloperMode()
            val confirmationText =
                "🔓 **Developer Mode Unlocked**\n\n" +
                    "Developer controls, Specialist overrides, and TESSA Turbo V1.2 Model Provider & Diagnostics settings are now active."
            val unlockAssistantMsg = MessageEntity(
                id = "msg-assistant-" + UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "assistant",
                content = confirmationText,
                createdAt = System.currentTimeMillis(),
                replyToId = userMsg.id
            )
            messageDao.insertMessage(unlockAssistantMsg)

            val existingConv = conversationDao.getConversationById(conversationId)
            if (existingConv != null) {
                val updatedTitle = if (existingConv.preview == null) "Developer Controls" else existingConv.title
                conversationDao.updateConversation(
                    existingConv.copy(
                        title = updatedTitle,
                        preview = "Developer Mode Unlocked",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            return unlockAssistantMsg
        }

        // Fetch recent messages for context (excluding any developer unlock command messages)
        val existingMessages = messageDao.getMessagesForConversationOnce(conversationId)
        val history = existingMessages
            .filterNot { isDeveloperUnlockCommand(it.content) }
            .takeLast(10)
            .map { it.role to it.content }

        val activeMode = AssistantEngineMode.fromConfigString(_developerConfig.value.activeAssistantMode)

        // Generate response according to selected AssistantEngineMode
        val replyText = when (activeMode) {
            AssistantEngineMode.MAYA_ONLINE -> {
                if (customOnlineProvider != null) {
                    modeRouter.routeRequest(
                        mode = activeMode,
                        prompt = content,
                        history = history,
                        character = _selectedCharacter.value,
                        config = _developerConfig.value,
                        tessaAgent = tessaAgent
                    ).replyText
                } else {
                    GeminiService.generateResponse(
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
                }
            }
            AssistantEngineMode.MAYA_OFFLINE,
            AssistantEngineMode.TESSA_TURBO_V1,
            AssistantEngineMode.SIMBA_TURBO_V2 -> {
                modeRouter.routeRequest(
                    mode = activeMode,
                    prompt = content,
                    history = history,
                    character = _selectedCharacter.value,
                    config = _developerConfig.value,
                    tessaAgent = tessaAgent
                ).replyText
            }
        }

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
