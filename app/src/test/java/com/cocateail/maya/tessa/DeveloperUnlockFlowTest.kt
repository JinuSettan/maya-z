package com.cocateail.maya.tessa

import android.app.Application
import android.content.SharedPreferences
import com.cocateail.maya.data.db.ConversationDao
import com.cocateail.maya.data.db.ConversationEntity
import com.cocateail.maya.data.db.MessageDao
import com.cocateail.maya.data.db.MessageEntity
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.provider.LlmRequest
import com.cocateail.maya.data.repository.MayaRepository
import com.cocateail.maya.ui.viewmodel.MayaViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeveloperUnlockFlowTest {

    /**
     * Real in-memory SharedPreferences implementation to test persistence across repository/app restarts.
     */
    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals.add(key)
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
            }

            override fun commit(): Boolean {
                applyChanges()
                return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun applyChanges() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                data.putAll(pending)
            }
        }
    }

    private class FakeConversationDao : ConversationDao {
        private val state = MutableStateFlow<List<ConversationEntity>>(emptyList())
        override fun getAllConversations(): Flow<List<ConversationEntity>> = state
        override suspend fun getConversationById(id: String): ConversationEntity? =
            state.value.find { it.id == id }

        override suspend fun insertConversation(conversation: ConversationEntity) {
            state.value = state.value.filterNot { it.id == conversation.id } + conversation
        }

        override suspend fun updateConversation(conversation: ConversationEntity) {
            state.value = state.value.map { if (it.id == conversation.id) conversation else it }
        }

        override suspend fun deleteConversationById(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }
    }

    private class FakeMessageDao : MessageDao {
        private val state = MutableStateFlow<List<MessageEntity>>(emptyList())
        override fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
            state.map { list -> list.filter { it.conversationId == conversationId } }

        override suspend fun getMessagesForConversationOnce(conversationId: String): List<MessageEntity> =
            state.value.filter { it.conversationId == conversationId }

        override suspend fun getMessageById(id: String): MessageEntity? =
            state.value.find { it.id == id }

        override suspend fun insertMessage(message: MessageEntity) {
            state.value = state.value.filterNot { it.id == message.id } + message
        }

        override suspend fun updateMessagePinned(id: String, pinned: Boolean) {
            state.value = state.value.map { if (it.id == id) it.copy(pinned = pinned) else it }
        }

        override fun searchMessagesForConversation(conversationId: String, query: String): Flow<List<MessageEntity>> =
            state.map { list ->
                list.filter { it.conversationId == conversationId && it.content.contains(query, ignoreCase = true) }
            }

        override fun searchAllMessages(query: String): Flow<List<MessageEntity>> =
            state.map { list -> list.filter { it.content.contains(query, ignoreCase = true) } }

        override suspend fun deleteMessagesForConversation(conversationId: String) {
            state.value = state.value.filterNot { it.conversationId == conversationId }
        }
    }

    private class SpyMayaOfflineProvider : MayaOfflineProvider {
        override val providerId: String = "spy_offline_qwen"
        var callCount = 0
        var lastPrompt: String? = null
        override suspend fun generateOffline(request: LlmRequest): String? {
            callCount++
            lastPrompt = request.prompt
            return "Offline Qwen response"
        }
    }

    private class SpyMayaOnlineProvider : MayaOnlineProvider {
        override val providerId: String = "spy_online_gemini"
        var callCount = 0
        var lastPrompt: String? = null
        override suspend fun generateOnline(request: LlmRequest): String {
            callCount++
            lastPrompt = request.prompt
            return "Online Gemini response"
        }
    }

    private class SpyTessaModelProvider : TessaModelProvider {
        override val providerName: String = "SpyTessaModelProvider"
        var callCount = 0
        var lastPrompt: String? = null
        override fun isAvailable(config: DeveloperConfig): Boolean = config.isTessaProviderConfigured()
        override fun inspectDiagnostics(config: DeveloperConfig): TessaDiagnosticsInfo {
            return TessaHttpModelProvider().inspectDiagnostics(config)
        }
        override suspend fun testConnection(config: DeveloperConfig): TessaDiagnosticsInfo {
            return inspectDiagnostics(config)
        }
        override suspend fun generate(request: TessaModelRequest): TessaModelResponse {
            callCount++
            lastPrompt = request.userPrompt
            return TessaModelResponse(
                available = true,
                content = "Tessa response",
                modelIdUsed = request.tessaModelId,
                providerEndpointUsed = request.tessaApiBaseUrl
            )
        }
    }

    // 1. Exact command unlocks developer mode
    @Test
    fun `1 exact command unlocks developer mode and returns confirmation message`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val convDao = FakeConversationDao()
        val msgDao = FakeMessageDao()
        val offlineSpy = SpyMayaOfflineProvider()
        val onlineSpy = SpyMayaOnlineProvider()
        val tessaSpy = SpyTessaModelProvider()

        val repo = MayaRepository(
            context = null,
            conversationDao = convDao,
            messageDao = msgDao,
            customPrefs = prefs,
            customOfflineProvider = offlineSpy,
            customOnlineProvider = onlineSpy,
            customTessaModelProvider = tessaSpy
        )

        assertFalse("Initially locked", repo.developerUnlocked.value)
        assertFalse("Config initially locked", repo.developerConfig.value.developerUnlocked)

        val conv = repo.createConversation()
        val reply = repo.sendMessage(
            conversationId = conv.id,
            content = "/developerceo/maya/unlockcode/trainer"
        )

        assertTrue("Developer mode must be unlocked", repo.developerUnlocked.value)
        assertTrue("DeveloperConfig.developerUnlocked must be true", repo.developerConfig.value.developerUnlocked)
        assertTrue(
            "Confirmation message must mention Developer Mode Unlocked",
            reply.content.contains("Developer Mode Unlocked")
        )
    }

    // 2. Unlock command is NEVER sent to the AI model (Gemini, Qwen, or TESSA)
    @Test
    fun `2 unlock command is intercepted BEFORE Gemini Qwen or Tessa and omitted from future history`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val convDao = FakeConversationDao()
        val msgDao = FakeMessageDao()
        val offlineSpy = SpyMayaOfflineProvider()
        val onlineSpy = SpyMayaOnlineProvider()
        val tessaSpy = SpyTessaModelProvider()

        val repo = MayaRepository(
            context = null,
            conversationDao = convDao,
            messageDao = msgDao,
            customPrefs = prefs,
            customOfflineProvider = offlineSpy,
            customOnlineProvider = onlineSpy,
            customTessaModelProvider = tessaSpy
        )

        val conv = repo.createConversation()

        // Test in MAYA_ONLINE mode
        repo.sendMessage(conv.id, "/developerceo/maya/unlockcode/trainer")
        assertEquals("Gemini must NOT be called for unlock command", 0, onlineSpy.callCount)
        assertEquals("Qwen must NOT be called for unlock command", 0, offlineSpy.callCount)
        assertEquals("Tessa must NOT be called for unlock command", 0, tessaSpy.callCount)

        // Test in MAYA_OFFLINE mode
        repo.saveDeveloperConfig(repo.developerConfig.value.copy(activeAssistantMode = "MAYA_OFFLINE"))
        repo.sendMessage(conv.id, "/developerceo/maya/unlockcode/trainer")
        assertEquals("Qwen must NOT be called for unlock command", 0, offlineSpy.callCount)

        // Test in TESSA_TURBO_V1 mode
        repo.saveDeveloperConfig(
            repo.developerConfig.value.copy(
                activeAssistantMode = "TESSA_TURBO_V1",
                tessaModelId = "tessa-model",
                tessaApiBaseUrl = "https://api.tessa.ai/v1",
                tessaApiKey = "secret_key"
            )
        )
        repo.sendMessage(conv.id, "/developerceo/maya/unlockcode/trainer")
        assertEquals("Tessa must NOT be called for unlock command", 0, tessaSpy.callCount)
    }

    // 3. Unlocked state persists after recreation/restart and config saves
    @Test
    fun `3 unlocked state persists across repository recreation and subsequent config updates`() = runBlocking {
        val sharedPrefs = InMemorySharedPreferences()
        val convDao = FakeConversationDao()
        val msgDao = FakeMessageDao()

        // First instance: unlock via chat command
        val repoInstance1 = MayaRepository(
            context = null,
            conversationDao = convDao,
            messageDao = msgDao,
            customPrefs = sharedPrefs,
            customOnlineProvider = SpyMayaOnlineProvider()
        )
        val conv = repoInstance1.createConversation()
        repoInstance1.sendMessage(conv.id, "/developerceo/maya/unlockcode/trainer")
        assertTrue(repoInstance1.developerUnlocked.value)

        // Recreate repository (simulating reopening Settings / restarting app)
        val repoInstance2 = MayaRepository(
            context = null,
            conversationDao = convDao,
            messageDao = msgDao,
            customPrefs = sharedPrefs,
            customOnlineProvider = SpyMayaOnlineProvider()
        )
        assertTrue("Unlocked state must persist after restart", repoInstance2.developerUnlocked.value)
        assertTrue("DeveloperConfig.developerUnlocked must persist after restart", repoInstance2.developerConfig.value.developerUnlocked)

        // Saving updated TESSA config from Developer Panel must also preserve unlocked state
        repoInstance2.saveDeveloperConfig(
            DeveloperConfig(
                tessaModelId = "tessa-pro-v1.2",
                tessaApiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
                tessaApiKey = "secret_tessa_key"
            )
        )
        val repoInstance3 = MayaRepository(
            context = null,
            conversationDao = convDao,
            messageDao = msgDao,
            customPrefs = sharedPrefs,
            customOnlineProvider = SpyMayaOnlineProvider()
        )
        assertTrue("Unlocked state must remain true after saving DeveloperConfig", repoInstance3.developerUnlocked.value)
        assertEquals("tessa-pro-v1.2", repoInstance3.developerConfig.value.tessaModelId)
    }

    // 4. Similar but incorrect commands do NOT unlock
    @Test
    fun `4 similar but incorrect commands do not unlock developer mode`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val convDao = FakeConversationDao()
        val msgDao = FakeMessageDao()
        val onlineSpy = SpyMayaOnlineProvider()

        val repo = MayaRepository(
            context = null,
            conversationDao = convDao,
            messageDao = msgDao,
            customPrefs = prefs,
            customOnlineProvider = onlineSpy
        )
        val conv = repo.createConversation()

        val invalidCommands = listOf(
            "/developerceo/maya/unlockcode",
            "/developerceo/maya/unlockcode/trainer2",
            "/developerceo/maya/unlockcode/TRAINER",
            "developerceo/maya/unlockcode/trainer",
            "/developerceo/maya/unlock/trainer",
            "unlock /developerceo/maya/unlockcode/trainer",
            "/developerceo/maya/unlockcode/trainer extra"
        )

        for (invalid in invalidCommands) {
            repo.sendMessage(conv.id, invalid)
            assertFalse("Command '$invalid' must NOT unlock developer mode", repo.developerUnlocked.value)
            assertFalse("Config must remain locked for '$invalid'", repo.developerConfig.value.developerUnlocked)
        }

        // And because they were normal messages, they were routed to the online provider
        assertEquals(invalidCommands.size, onlineSpy.callCount)
    }

    // 5. TESSA Developer Panel becomes accessible after successful unlock
    @Test
    fun `5 TESSA Developer Panel becomes accessible after successful unlock and exposes TESSA diagnostics`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val convDao = FakeConversationDao()
        val msgDao = FakeMessageDao()
        val onlineSpy = SpyMayaOnlineProvider()

        val repo = MayaRepository(
            context = null,
            conversationDao = convDao,
            messageDao = msgDao,
            customPrefs = prefs,
            customOnlineProvider = onlineSpy
        )

        val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val viewModel = MayaViewModel(Application(), repo, testScope)

        // Before unlock: attempting to open Developer Panel must be blocked
        assertFalse(viewModel.developerUnlocked.value)
        assertFalse(viewModel.showDeveloperPanel.value)
        viewModel.openDeveloperPanel()
        assertFalse("Developer Panel must NOT open when locked", viewModel.showDeveloperPanel.value)

        // Send exact unlock command through normal chat ViewModel flow
        viewModel.sendMessage("/developerceo/maya/unlockcode/trainer", null, null)

        // Immediately unlocked and Developer Panel opened
        assertTrue("ViewModel developerUnlocked must be true", viewModel.developerUnlocked.value)
        assertTrue("Developer Panel must automatically open on unlock", viewModel.showDeveloperPanel.value)
        assertEquals("Unlock command must not reach online provider", 0, onlineSpy.callCount)

        // Dismiss and re-open Developer Panel (as from Settings -> Open Developer Panel)
        viewModel.dismissDeveloperPanel()
        assertFalse(viewModel.showDeveloperPanel.value)
        viewModel.openDeveloperPanel()
        assertTrue("Developer Panel must open when unlocked", viewModel.showDeveloperPanel.value)

        // Configure TESSA Provider from the unlocked Developer Panel and verify diagnostics
        viewModel.saveDeveloperConfig(
            viewModel.developerConfig.value.copy(
                tessaModelId = "tessa-1.2-turbo",
                tessaApiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
                tessaApiKey = "secret_key_do_not_expose"
            )
        )
        val diag = repo.tessaAgent.modelProvider.inspectDiagnostics(viewModel.developerConfig.value)
        assertEquals("TessaHttpModelProvider", diag.providerClass)
        assertTrue(diag.isModelIdConfigured)
        assertTrue(diag.isEndpointConfigured)
        assertTrue(diag.isApiKeyConfigured)
        assertTrue(diag.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_READY, diag.runtimeStatus)
        assertFalse("Diagnostics must NEVER expose the raw API key", diag.statusMessage.contains("secret_key_do_not_expose"))
    }
}
