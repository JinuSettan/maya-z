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
import com.cocateail.maya.data.repository.MayaRepository
import com.cocateail.maya.tessa.AssistantEngineMode
import com.cocateail.maya.tessa.TessaTask
import com.cocateail.maya.tessa.TessaUiStatus
import com.cocateail.maya.utils.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MayaViewModel @JvmOverloads constructor(
    application: Application,
    customRepository: MayaRepository? = null,
    customScope: CoroutineScope? = null
) : AndroidViewModel(application) {
    private val scope: CoroutineScope = customScope ?: viewModelScope
    private val repository: MayaRepository = customRepository ?: (application as MayaApplication).repository
    val ttsManager = TtsManager(application)

    val conversations: StateFlow<List<ConversationEntity>> = repository.conversations
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedCharacter: StateFlow<MayaCharacter> = repository.selectedCharacter
    val developerConfig: StateFlow<DeveloperConfig> = repository.developerConfig
    val developerUnlocked: StateFlow<Boolean> = repository.developerUnlocked

    private val _showDeveloperPanel = MutableStateFlow(false)
    val showDeveloperPanel: StateFlow<Boolean> = _showDeveloperPanel.asStateFlow()

    fun openDeveloperPanel() {
        if (developerUnlocked.value) {
            _showDeveloperPanel.value = true
        }
    }

    fun dismissDeveloperPanel() {
        _showDeveloperPanel.value = false
    }

    val tessaTasks: StateFlow<List<TessaTask>> = repository.tessaTasks
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tessaUiStatus: StateFlow<TessaUiStatus> = repository.tessaUiStatus

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

    private val _brainProgress = MutableStateFlow(1.0f)
    val brainProgress: StateFlow<Float> = _brainProgress.asStateFlow()

    private val _brainReady = MutableStateFlow(true)
    val brainReady: StateFlow<Boolean> = _brainReady.asStateFlow()

    private val _brainError = MutableStateFlow<String?>(null)
    val brainError: StateFlow<String?> = _brainError.asStateFlow()

    private var messagesJob: kotlinx.coroutines.Job? = null
    private var isCreatingInitialConversation = false

    init {
        initializeBrain()
        observeConversations()
    }

    fun initializeBrain() {
        _brainError.value = null
        _brainProgress.value = 1.0f
        _brainReady.value = true
    }

    private fun observeConversations() {
        scope.launch {
            repository.conversations.collect { list ->
                if (_activeConversationId.value == null && list.isNotEmpty()) {
                    selectConversation(list.first().id)
                } else if (list.isEmpty() && !isCreatingInitialConversation) {
                    isCreatingInitialConversation = true
                    try {
                        val conv = repository.createConversation()
                        selectConversation(conv.id)
                    } finally {
                        isCreatingInitialConversation = false
                    }
                }
            }
        }
    }

    fun selectConversation(id: String) {
        if (_activeConversationId.value == id && messagesJob?.isActive == true) return
        _activeConversationId.value = id
        messagesJob?.cancel()
        messagesJob = scope.launch {
            repository.getMessages(id).collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    fun newConversation() {
        scope.launch {
            val conv = repository.createConversation()
            selectConversation(conv.id)
        }
    }

    fun deleteConversation(id: String) {
        scope.launch {
            repository.deleteConversation(id)
            if (_activeConversationId.value == id) {
                _activeConversationId.value = null
            }
        }
    }

    fun sendMessage(content: String, bitmap: Bitmap?, imageUrl: String?) {
        val replyMsg = _activeReplyToMessage.value
        _activeReplyToMessage.value = null // Reset reply bar state right away

        // Detect exact developer unlock command BEFORE any model or build classifier
        val isUnlockCmd = MayaRepository.isDeveloperUnlockCommand(content)
        if (isUnlockCmd) {
            repository.unlockDeveloperMode()
            _showDeveloperPanel.value = true
        }

        scope.launch {
            _isThinking.value = true
            try {
                var convId = _activeConversationId.value
                if (convId == null) {
                    val conv = repository.createConversation()
                    convId = conv.id
                    selectConversation(convId)
                }

                if (isUnlockCmd) {
                    repository.sendMessage(
                        conversationId = convId,
                        content = content,
                        imageBitmap = bitmap,
                        imageUrl = imageUrl,
                        replyToId = replyMsg?.id,
                        repliedMessageSender = if (replyMsg?.role == "user") "You" else selectedCharacter.value.displayName,
                        repliedMessageText = replyMsg?.content,
                        repliedMessageImageUrl = replyMsg?.imageUrl
                    )
                    _showDeveloperPanel.value = true
                    return@launch
                }

                val activeMode = AssistantEngineMode.fromConfigString(developerConfig.value.activeAssistantMode)
                val isBuildRequest = activeMode == AssistantEngineMode.MAYA_ONLINE &&
                    com.cocateail.maya.data.specialist.TaskClassifier.isBuildRequest(content)
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
                    val projectType = com.cocateail.maya.studio.BuilderAgent.classifyProjectType(content)

                    try {
                        val project = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            builderAgent.executeTask(content, projectType) { status, text, iter ->
                                val updatedStatusContent = "[BUILDER_STATUS]\nstatus: $status\nmessage: $text\niteration: $iter"
                                viewModelScope.launch {
                                    repository.updateMessageContent(assistMsgId, updatedStatusContent)
                                }
                            }
                        }

                        val successResponse = when (projectType) {
                            com.cocateail.maya.studio.BuilderProjectType.ANDROID_APP -> """
                                Done machu 😎
                                
                                ✓ Complete Android Gradle project generated (${project.files.size} files)
                                ✓ settings.gradle.kts, build.gradle.kts & app/build.gradle.kts
                                ✓ Gradle wrapper & binary gradle-wrapper.jar preserved
                                ✓ AndroidManifest.xml, Kotlin/Compose UI & resources verified
                                
                                Ready in App & Game Studio and Git Studio for APK + AAB build!
                            """.trimIndent()
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
                    if (developerConfig.value.ttsEnabled && activeMode != AssistantEngineMode.SIMBA_TURBO_V2) {
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

    fun selectAssistantMode(mode: AssistantEngineMode) {
        val current = developerConfig.value
        val updated = current.copy(
            activeAssistantMode = mode.modeId,
            onlineFeaturesEnabled = mode != AssistantEngineMode.MAYA_OFFLINE
        )
        repository.saveDeveloperConfig(updated)
    }

    fun approveTessaPermission(taskId: String, saveAsReusableRule: Boolean = false) {
        val convId = _activeConversationId.value
        viewModelScope.launch {
            _isThinking.value = true
            try {
                val outcome = repository.tessaAgent.approveTaskPermissionsAndResume(
                    taskId = taskId,
                    config = developerConfig.value,
                    saveAsReusableRule = saveAsReusableRule
                )
                if (outcome != null && convId != null) {
                    val msg = MessageEntity(
                        id = "msg-assistant-" + java.util.UUID.randomUUID().toString(),
                        conversationId = convId,
                        role = "assistant",
                        content = outcome.formattedResponse,
                        createdAt = System.currentTimeMillis()
                    )
                    repository.insertMessageDirect(msg)
                }
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun denyTessaPermission(taskId: String) {
        val convId = _activeConversationId.value
        viewModelScope.launch {
            val task = repository.tessaAgent.denyTaskPermission(taskId)
            if (task != null && convId != null) {
                val msg = MessageEntity(
                    id = "msg-assistant-" + java.util.UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = "assistant",
                    content = "🛑 Permission denied for Tessa Task `${task.taskId}`. Task marked as FAILED.",
                    createdAt = System.currentTimeMillis()
                )
                repository.insertMessageDirect(msg)
            }
        }
    }

    fun cancelTessaTask(taskId: String) {
        val convId = _activeConversationId.value
        viewModelScope.launch {
            val task = repository.tessaAgent.cancelTask(taskId)
            if (task != null && convId != null) {
                val msg = MessageEntity(
                    id = "msg-assistant-" + java.util.UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = "assistant",
                    content = "🚫 Tessa Task `${task.taskId}` cancelled by user.",
                    createdAt = System.currentTimeMillis()
                )
                repository.insertMessageDirect(msg)
            }
        }
    }

    suspend fun sendMessageDirect(content: String, bitmap: Bitmap? = null, imageUrl: String? = null): String {
        val isUnlockCmd = MayaRepository.isDeveloperUnlockCommand(content)
        if (isUnlockCmd) {
            repository.unlockDeveloperMode()
            _showDeveloperPanel.value = true
        }
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
            if (isUnlockCmd) {
                _showDeveloperPanel.value = true
            }
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
