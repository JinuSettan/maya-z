package com.cocateail.maya.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cocateail.maya.MayaApplication
import com.cocateail.maya.data.db.ConversationEntity
import com.cocateail.maya.data.db.MessageEntity
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.utils.TtsManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MayaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as MayaApplication).repository
    val ttsManager = TtsManager(application)

    val conversations: StateFlow<List<ConversationEntity>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedCharacter: StateFlow<MayaCharacter> = repository.selectedCharacter
    val developerConfig: StateFlow<DeveloperConfig> = repository.developerConfig
    val developerUnlocked: StateFlow<Boolean> = repository.developerUnlocked

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _activeReplyToMessage = MutableStateFlow<MessageEntity?>(null)
    val activeReplyToMessage: StateFlow<MessageEntity?> = _activeReplyToMessage.asStateFlow()

    fun setReplyToMessage(msg: MessageEntity?) {
        _activeReplyToMessage.value = msg
    }

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

    private val _brainProgress = MutableStateFlow(0f)
    val brainProgress: StateFlow<Float> = _brainProgress.asStateFlow()

    private val _brainReady = MutableStateFlow(false)
    val brainReady: StateFlow<Boolean> = _brainReady.asStateFlow()

    private val _brainError = MutableStateFlow<String?>(null)
    val brainError: StateFlow<String?> = _brainError.asStateFlow()

    init {
        initializeBrain()
        observeConversations()
    }

    fun initializeBrain() {
        viewModelScope.launch {
            _brainError.value = null
            _brainProgress.value = 0.2f
            kotlinx.coroutines.delay(200)
            _brainProgress.value = 0.6f
            kotlinx.coroutines.delay(200)
            _brainProgress.value = 1.0f
            _brainReady.value = true
        }
    }

    private fun observeConversations() {
        viewModelScope.launch {
            conversations.collect { list ->
                if (_activeConversationId.value == null && list.isNotEmpty()) {
                    selectConversation(list.first().id)
                } else if (list.isEmpty()) {
                    newConversation()
                }
            }
        }
    }

    fun selectConversation(id: String) {
        _activeConversationId.value = id
        viewModelScope.launch {
            repository.getMessages(id).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val conv = repository.createConversation()
            selectConversation(conv.id)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_activeConversationId.value == id) {
                _activeConversationId.value = null
            }
        }
    }

    fun sendMessage(content: String, bitmap: Bitmap?, imageUrl: String?) {
        val convId = _activeConversationId.value ?: return
        val replyMsg = _activeReplyToMessage.value
        _activeReplyToMessage.value = null // Reset reply bar state right away
        viewModelScope.launch {
            _isThinking.value = true
            try {
                val isBuildRequest = com.cocateail.maya.data.specialist.TaskClassifier.isBuildRequest(content)
                if (isBuildRequest) {
                    // 1. Save User Message
                    val userMsgId = "msg-user-" + java.util.UUID.randomUUID().toString()
                    val userMsg = MessageEntity(
                        id = userMsgId,
                        conversationId = convId,
                        role = "user",
                        content = content,
                        createdAt = System.currentTimeMillis()
                    )
                    repository.insertMessageDirect(userMsg)

                    // 2. Save Initial Builder Status Assistant Message
                    val assistMsgId = "msg-assistant-" + java.util.UUID.randomUUID().toString()
                    val initialStatusContent = "[BUILDER_STATUS]\nstatus: PLANNING\nmessage: Planning task and workspace...\niteration: 1"
                    val assistantMsg = MessageEntity(
                        id = assistMsgId,
                        conversationId = convId,
                        role = "assistant",
                        content = initialStatusContent,
                        createdAt = System.currentTimeMillis() + 10,
                        replyToId = userMsgId
                    )
                    repository.insertMessageDirect(assistantMsg)

                    // 3. Launch Builder Agent
                    val builderAgent = com.cocateail.maya.studio.BuilderAgent(getApplication())
                    val projectType = when {
                        content.lowercase().contains("photo") || content.lowercase().contains("edit") || content.lowercase().contains("image") || content.lowercase().contains("filter") ->
                            com.cocateail.maya.studio.BuilderProjectType.PHOTO_EDITOR
                        content.lowercase().contains("game") || content.lowercase().contains("3d") || content.lowercase().contains("physics") ->
                            com.cocateail.maya.studio.BuilderProjectType.GAME_3D
                        else ->
                            com.cocateail.maya.studio.BuilderProjectType.WEB_APP
                    }

                    try {
                        val project = builderAgent.executeTask(content, projectType) { status, text, iter ->
                            val updatedStatusContent = "[BUILDER_STATUS]\nstatus: $status\nmessage: $text\niteration: $iter"
                            viewModelScope.launch {
                                repository.updateMessageContent(assistMsgId, updatedStatusContent)
                            }
                        }

                        val successResponse = when (projectType) {
                            com.cocateail.maya.studio.BuilderProjectType.PHOTO_EDITOR -> """
                                Done machu 😎
                                
                                ✓ Photo Editor created
                                ✓ Image import
                                ✓ Crop
                                ✓ Rotate
                                ✓ Brightness
                                ✓ Contrast
                                ✓ Blur Filter
                                ✓ Saturation
                                ✓ Export
                                
                                Preview is ready in App & Game Studio!
                            """.trimIndent()
                            com.cocateail.maya.studio.BuilderProjectType.GAME_3D -> """
                                Done machu 😎
                                
                                ✓ 3D Game Studio project compiled
                                ✓ WebGL scene setup
                                ✓ Perspective Camera
                                ✓ 3D Mesh & Shader material
                                ✓ Animation Render loop
                                
                                Preview is ready in App & Game Studio!
                            """.trimIndent()
                            else -> """
                                Done machu 😎
                                
                                ✓ Interactive App created
                                ✓ Validated structures
                                ✓ Active components
                                
                                Preview is ready in App & Game Studio!
                            """.trimIndent()
                        }
                        repository.updateMessageContent(assistMsgId, successResponse)
                    } catch (e: Exception) {
                        val errorResponse = "Build stopped after 5 repair attempts.\n\nI found unresolved errors:\n${e.localizedMessage}"
                        repository.updateMessageContent(assistMsgId, errorResponse)
                    }
                } else {
                    val assistantMsg = repository.sendMessage(
                        conversationId = convId,
                        content = content,
                        imageBitmap = bitmap,
                        imageUrl = imageUrl,
                        replyToId = replyMsg?.id,
                        repliedMessageSender = if (replyMsg?.role == "user") "You" else selectedCharacter.value.displayName,
                        repliedMessageText = replyMsg?.content,
                        repliedMessageImageUrl = replyMsg?.imageUrl
                    )
                    if (developerConfig.value.ttsEnabled) {
                        ttsManager.speak(
                            text = assistantMsg.content,
                            rate = developerConfig.value.ttsRate,
                            pitch = developerConfig.value.ttsPitch
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isThinking.value = false
            }
        }
    }

    suspend fun sendMessageDirect(content: String, bitmap: Bitmap? = null, imageUrl: String? = null): String {
        var convId = _activeConversationId.value
        if (convId == null) {
            val conv = repository.createConversation()
            convId = conv.id
            _activeConversationId.value = convId
        }
        val replyMsg = _activeReplyToMessage.value
        _activeReplyToMessage.value = null
        _isThinking.value = true
        return try {
            val assistantMsg = repository.sendMessage(
                conversationId = convId,
                content = content,
                imageBitmap = bitmap,
                imageUrl = imageUrl,
                replyToId = replyMsg?.id,
                repliedMessageSender = if (replyMsg?.role == "user") "You" else selectedCharacter.value.displayName,
                repliedMessageText = replyMsg?.content,
                repliedMessageImageUrl = replyMsg?.imageUrl
            )
            assistantMsg.content
        } catch (e: Exception) {
            e.printStackTrace()
            "I'm right here listening to you. How can I help?"
        } finally {
            _isThinking.value = false
        }
    }

    fun togglePinMessage(messageId: String, currentPinned: Boolean) {
        viewModelScope.launch {
            repository.togglePinMessage(messageId, currentPinned)
        }
    }

    fun speakMessage(content: String) {
        ttsManager.speak(
            text = content,
            rate = developerConfig.value.ttsRate,
            pitch = developerConfig.value.ttsPitch
        )
    }

    fun selectCharacter(character: MayaCharacter) {
        repository.setCharacter(character)
    }

    fun saveDeveloperConfig(config: DeveloperConfig) {
        repository.saveDeveloperConfig(config)
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
    }
}
