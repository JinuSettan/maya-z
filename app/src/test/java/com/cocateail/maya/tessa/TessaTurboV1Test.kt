package com.cocateail.maya.tessa

import com.cocateail.maya.data.db.TessaTaskDao
import com.cocateail.maya.data.db.TessaTaskEntity
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.data.provider.LlmRequest
import com.cocateail.maya.studio.BuilderProjectType
import com.cocateail.maya.ui.components.GeneratedProject
import com.cocateail.maya.ui.components.ProjectFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TessaTurboV1Test {

    // Fake DAO backing onto an in-memory map to test Room persistence round-trips deterministically
    private class FakeTessaTaskDao : TessaTaskDao {
        val table = linkedMapOf<String, TessaTaskEntity>()
        private val flow = MutableStateFlow<List<TessaTaskEntity>>(emptyList())

        override fun observeAllTasks(): Flow<List<TessaTaskEntity>> = flow

        override suspend fun getAllTasksOnce(): List<TessaTaskEntity> =
            table.values.sortedByDescending { it.updatedAt }

        override suspend fun getTaskById(taskId: String): TessaTaskEntity? = table[taskId]

        override suspend fun getLatestTaskForProject(projectName: String): TessaTaskEntity? =
            table.values
                .filter { it.relatedProject?.equals(projectName, ignoreCase = true) == true }
                .maxByOrNull { it.updatedAt }

        override suspend fun getActiveTasksOnce(): List<TessaTaskEntity> =
            table.values.filter {
                it.status in setOf("QUEUED", "RUNNING", "WAITING_FOR_PERMISSION", "PAUSED")
            }

        override suspend fun upsertTask(task: TessaTaskEntity) {
            table[task.taskId] = task
            flow.value = getAllTasksOnce()
        }

        override suspend fun updateTask(task: TessaTaskEntity) {
            table[task.taskId] = task
            flow.value = getAllTasksOnce()
        }

        override suspend fun deleteTaskById(taskId: String) {
            table.remove(taskId)
            flow.value = getAllTasksOnce()
        }
    }

    private class RecordingMayaOfflineProvider(var reply: String? = "Offline Qwen2.5 response") : MayaOfflineProvider {
        override val providerId: String = "test_maya_offline"
        var callCount = 0
        override suspend fun generateOffline(request: LlmRequest): String? {
            callCount++
            return reply
        }
    }

    private class RecordingMayaOnlineProvider(var reply: String = "Online Gemini response") : MayaOnlineProvider {
        override val providerId: String = "test_maya_online"
        var callCount = 0
        override suspend fun generateOnline(request: LlmRequest): String {
            callCount++
            return reply
        }
    }

    private class ConfigurableTessaModelProvider(
        private val simulateSuccess: Boolean = true,
        private val responseText: String = "Tessa dedicated model output"
    ) : TessaModelProvider {
        override val providerName: String = "TestDedicatedTessaProvider"
        var callCount = 0
        var lastRequest: TessaModelRequest? = null

        override fun isAvailable(config: DeveloperConfig): Boolean = config.isTessaProviderConfigured()

        override fun inspectDiagnostics(config: DeveloperConfig): TessaDiagnosticsInfo {
            val ready = isAvailable(config)
            return TessaDiagnosticsInfo(
                providerClass = providerName,
                isModelIdConfigured = config.tessaModelId.isNotBlank(),
                isEndpointConfigured = config.tessaApiBaseUrl.isNotBlank(),
                isApiKeyConfigured = config.tessaApiKey.isNotBlank(),
                isProviderReachable = ready && simulateSuccess,
                isRuntimeReady = ready,
                runtimeStatus = if (ready) TessaRuntimeStatus.TESSA_READY else TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED,
                statusMessage = if (ready) "Configured" else "Config required"
            )
        }

        override suspend fun testConnection(config: DeveloperConfig): TessaDiagnosticsInfo {
            val diag = inspectDiagnostics(config)
            return if (diag.isRuntimeReady && simulateSuccess) {
                diag.copy(
                    isProviderReachable = true,
                    runtimeStatus = TessaRuntimeStatus.TESSA_READY,
                    statusMessage = "SUCCESS: Provider ready"
                )
            } else {
                diag.copy(
                    isProviderReachable = false,
                    runtimeStatus = if (!diag.isRuntimeReady) TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED else TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE,
                    statusMessage = "FAILURE: Provider unavailable"
                )
            }
        }

        override suspend fun generate(request: TessaModelRequest): TessaModelResponse {
            callCount++
            lastRequest = request
            if (request.tessaModelId.isBlank() || request.tessaApiBaseUrl.isBlank() || request.tessaApiKey.isBlank()) {
                return TessaModelResponse(
                    available = false,
                    errorCode = "TESSA_PROVIDER_NOT_CONFIGURED",
                    errorMessage = "Missing tessaModelId, tessaApiBaseUrl, or tessaApiKey"
                )
            }
            return if (simulateSuccess) {
                TessaModelResponse(
                    available = true,
                    content = responseText,
                    modelIdUsed = request.tessaModelId,
                    providerEndpointUsed = request.tessaApiBaseUrl
                )
            } else {
                TessaModelResponse(
                    available = false,
                    modelIdUsed = request.tessaModelId,
                    providerEndpointUsed = request.tessaApiBaseUrl,
                    errorCode = "TESSA_PROVIDER_UNAVAILABLE",
                    errorMessage = "Configured Tessa endpoint unreachable"
                )
            }
        }
    }

    // 1. Mode selection
    @Test
    fun `1 mode selection resolves all four distinct modes and keeps SIMBA locked`() {
        assertEquals(AssistantEngineMode.MAYA_OFFLINE, AssistantEngineMode.fromConfigString("MAYA_OFFLINE"))
        assertEquals(AssistantEngineMode.MAYA_ONLINE, AssistantEngineMode.fromConfigString("MAYA_ONLINE"))
        assertEquals(AssistantEngineMode.TESSA_TURBO_V1, AssistantEngineMode.fromConfigString("TESSA_TURBO_V1"))
        assertEquals(AssistantEngineMode.SIMBA_TURBO_V2, AssistantEngineMode.fromConfigString("SIMBA_TURBO_V2"))

        assertFalse(AssistantEngineMode.MAYA_OFFLINE.isLocked)
        assertFalse(AssistantEngineMode.MAYA_ONLINE.isLocked)
        assertFalse(AssistantEngineMode.TESSA_TURBO_V1.isLocked)
        assertTrue(AssistantEngineMode.SIMBA_TURBO_V2.isLocked)
        assertTrue(AssistantEngineMode.SIMBA_TURBO_V2.displayName.contains("🔒"))
    }

    // 2. MAYA Offline routing
    @Test
    fun `2 MAYA Offline mode routes exclusively to MayaOfflineProvider`() = runBlocking {
        val offlineProvider = RecordingMayaOfflineProvider("Local Qwen GGUF reply")
        val onlineProvider = RecordingMayaOnlineProvider()
        val tessaProvider = ConfigurableTessaModelProvider()
        val router = TessaModelRouter(offlineProvider, onlineProvider, tessaProvider)

        val outcome = router.routeRequest(
            mode = AssistantEngineMode.MAYA_OFFLINE,
            prompt = "Hello offline Qwen",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = DeveloperConfig(activeAssistantMode = "MAYA_OFFLINE")
        )

        assertEquals(AssistantEngineMode.MAYA_OFFLINE, outcome.mode)
        assertEquals(1, offlineProvider.callCount)
        assertEquals(0, onlineProvider.callCount)
        assertEquals(0, tessaProvider.callCount)
        assertEquals("Local Qwen GGUF reply", outcome.replyText)
    }

    // 3. MAYA Online routing
    @Test
    fun `3 MAYA Online mode routes exclusively to MayaOnlineProvider`() = runBlocking {
        val offlineProvider = RecordingMayaOfflineProvider()
        val onlineProvider = RecordingMayaOnlineProvider("Gemini online intelligent reply")
        val tessaProvider = ConfigurableTessaModelProvider()
        val router = TessaModelRouter(offlineProvider, onlineProvider, tessaProvider)

        val outcome = router.routeRequest(
            mode = AssistantEngineMode.MAYA_ONLINE,
            prompt = "Hello online Gemini",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = DeveloperConfig(activeAssistantMode = "MAYA_ONLINE")
        )

        assertEquals(AssistantEngineMode.MAYA_ONLINE, outcome.mode)
        assertEquals(0, offlineProvider.callCount)
        assertEquals(1, onlineProvider.callCount)
        assertEquals(0, tessaProvider.callCount)
        assertEquals("Gemini online intelligent reply", outcome.replyText)
    }

    // 4. TESSA routing
    @Test
    fun `4 TESSA Turbo V1 routes exclusively through TessaAgent and TessaModelProvider`() = runBlocking {
        val offlineProvider = RecordingMayaOfflineProvider()
        val onlineProvider = RecordingMayaOnlineProvider()
        val tessaProvider = ConfigurableTessaModelProvider(responseText = "Tessa Turbo V1 dedicated reply")
        val router = TessaModelRouter(offlineProvider, onlineProvider, tessaProvider)
        val agent = TessaAgent(modelProvider = tessaProvider, taskStore = TessaTaskStore(FakeTessaTaskDao()))

        val cfg = DeveloperConfig(
            activeAssistantMode = "TESSA_TURBO_V1",
            tessaModelId = "tessa-reasoner-v1",
            tessaApiBaseUrl = "https://api.example.com/v1",
            tessaApiKey = "tessa_live_key_12345"
        )

        val outcome = router.routeRequest(
            mode = AssistantEngineMode.TESSA_TURBO_V1,
            prompt = "What is the status of our architecture?",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = cfg,
            tessaAgent = agent
        )

        assertEquals(AssistantEngineMode.TESSA_TURBO_V1, outcome.mode)
        assertEquals(0, offlineProvider.callCount)
        assertEquals(0, onlineProvider.callCount)
        assertEquals(1, tessaProvider.callCount)
        assertEquals("tessa-reasoner-v1", tessaProvider.lastRequest?.tessaModelId)
        assertEquals("Tessa Turbo V1 dedicated reply", outcome.replyText)
    }

    // 5. SIMBA locked state
    @Test
    fun `5 SIMBA Turbo V2 is strictly locked and never starts models tools or background tasks`() = runBlocking {
        val offlineProvider = RecordingMayaOfflineProvider()
        val onlineProvider = RecordingMayaOnlineProvider()
        val tessaProvider = ConfigurableTessaModelProvider()
        val router = TessaModelRouter(offlineProvider, onlineProvider, tessaProvider)
        val fakeDao = FakeTessaTaskDao()
        val agent = TessaAgent(modelProvider = tessaProvider, taskStore = TessaTaskStore(fakeDao))

        val outcome = router.routeRequest(
            mode = AssistantEngineMode.SIMBA_TURBO_V2,
            prompt = "Build an app and run tools",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = DeveloperConfig(activeAssistantMode = "SIMBA_TURBO_V2"),
            tessaAgent = agent
        )

        assertTrue(outcome.isLocked)
        assertFalse(outcome.providerAvailable)
        assertEquals("SIMBA_TURBO_V2_LOCKED", outcome.errorCode)
        assertEquals(0, offlineProvider.callCount)
        assertEquals(0, onlineProvider.callCount)
        assertEquals(0, tessaProvider.callCount)
        assertTrue(fakeDao.table.isEmpty())
    }

    // 6. Separate Tessa provider configuration
    @Test
    fun `6 Separate Tessa provider configuration does not reuse Maya model ID and returns real unavailable state when missing`() = runBlocking {
        val httpProvider = TessaHttpModelProvider()
        val unconfigured = DeveloperConfig(
            customApiKey = "AIzaSyMayaGeminiKey1234567890", // Maya key set, but Tessa config is empty
            tessaModelId = "",
            tessaApiBaseUrl = "",
            tessaApiKey = ""
        )
        assertFalse(httpProvider.isAvailable(unconfigured))

        val respMissingModel = httpProvider.generate(
            TessaModelRequest(
                userPrompt = "Hello Tessa",
                tessaModelId = unconfigured.tessaModelId,
                tessaApiBaseUrl = unconfigured.tessaApiBaseUrl,
                tessaApiKey = unconfigured.tessaApiKey
            )
        )
        assertFalse(respMissingModel.available)
        assertEquals("TESSA_MODEL_ID_MISSING", respMissingModel.errorCode)

        val agent = TessaAgent(modelProvider = httpProvider, taskStore = TessaTaskStore(FakeTessaTaskDao()))
        val outcome = agent.handleUserRequest("Explain quantum computing?", unconfigured)
        assertFalse(outcome.providerAvailable)
        assertEquals(TessaTaskState.FAILED, outcome.task.status)
        assertEquals("TESSA_MODEL_ID_MISSING", outcome.errorCode)
    }

    // 7. Tessa task creation
    @Test
    fun `7 Tessa task creation populates all required fields and classifies taskType`() = runBlocking {
        val dao = FakeTessaTaskDao()
        val agent = TessaAgent(
            modelProvider = ConfigurableTessaModelProvider(),
            taskStore = TessaTaskStore(dao)
        )
        val cfg = DeveloperConfig(
            tessaModelId = "tessa-v1",
            tessaApiBaseUrl = "https://api.example.com/v1",
            tessaApiKey = "secret_key_abc12345"
        )

        val outcome = agent.handleUserRequest(
            userRequest = "Build an Android app project WeatherTracker with Jetpack Compose",
            config = cfg
        )
        val task = outcome.task
        assertTrue(task.taskId.startsWith("tessa-task-"))
        assertTrue(task.createdAt > 0L)
        assertTrue(task.updatedAt >= task.createdAt)
        assertEquals(TessaTaskState.ACTION, task.taskType)
        assertEquals(TessaTaskState.WAITING_FOR_PERMISSION, task.status)
        assertTrue(task.requiresPermission)
        assertEquals(TessaPermissionState.PENDING_USER_APPROVAL, task.permissionState)
        assertTrue(task.requiredPermissions.contains(TessaPermissionType.WRITE_PROJECT))
        assertEquals("WeatherTracker", task.relatedProject)
    }

    // 8. Task persistence & secret redaction
    @Test
    fun `8 Task persistence survives store reload via Room DAO and redacts secrets`() = runBlocking {
        val dao = FakeTessaTaskDao()
        val store1 = TessaTaskStore(dao)
        val rawSecret = "ghp_1234567890abcdefghijklmnopqrstuv"
        val task = TessaTask(
            userRequest = "Push project using token $rawSecret",
            taskType = TessaTaskState.ACTION,
            status = TessaTaskState.WAITING_FOR_PERMISSION,
            requiresPermission = true,
            permissionState = TessaPermissionState.PENDING_USER_APPROVAL,
            requiredPermissions = listOf(TessaPermissionType.PUSH_GITHUB),
            relatedProject = "CustomerApp"
        )
        store1.saveTask(task, explicitSecrets = listOf(rawSecret))

        // Simulate process restart with a new TessaTaskStore instance reading from the same DAO
        val store2 = TessaTaskStore(dao)
        val reloaded = store2.getTaskById(task.taskId)
        assertNotNull(reloaded)
        assertFalse(reloaded!!.userRequest.contains(rawSecret))
        assertTrue(reloaded.userRequest.contains("[REDACTED"))
        assertEquals("CustomerApp", reloaded.relatedProject)
        assertEquals(TessaPermissionState.PENDING_USER_APPROVAL, reloaded.permissionState)
    }

    // 9. Permission checks
    @Test
    fun `9 Permission manager enforces explicit approval for external side effects and supports read-only reusable rules`() {
        val pm = TessaPermissionManager()

        // Read-only permission initially requires approval
        val initEval = pm.evaluatePermissions(
            taskId = "task-1",
            userRequest = "Read email attachment",
            requiredPermissions = listOf(TessaPermissionType.READ_EMAIL, TessaPermissionType.READ_PDF)
        )
        assertFalse(initEval.allowed)
        assertEquals(TessaPermissionState.PENDING_USER_APPROVAL, initEval.state)

        // Add reusable rule for read-only permissions
        assertTrue(pm.addReusablePermissionRule(TessaPermissionType.READ_EMAIL))
        assertTrue(pm.addReusablePermissionRule(TessaPermissionType.READ_PDF))

        val secondEval = pm.evaluatePermissions(
            taskId = "task-2",
            userRequest = "Read email attachment",
            requiredPermissions = listOf(TessaPermissionType.READ_EMAIL, TessaPermissionType.READ_PDF)
        )
        assertTrue(secondEval.allowed)
        assertEquals(TessaPermissionState.GRANTED_REUSABLE_RULE, secondEval.state)

        // External side-effect (PUSH_GITHUB) always requires explicit per-task approval even if someone tries reusable rule
        pm.addReusablePermissionRule(TessaPermissionType.PUSH_GITHUB)
        val pushEval = pm.evaluatePermissions(
            taskId = "task-3",
            userRequest = "Push to GitHub",
            requiredPermissions = listOf(TessaPermissionType.PUSH_GITHUB)
        )
        assertFalse(pushEval.allowed)
        assertEquals(TessaPermissionState.PENDING_USER_APPROVAL, pushEval.state)
    }

    // 10. Tool routing
    @Test
    fun `10 Tool router dispatches to registered tools and records audit trail`() = runBlocking {
        val pm = TessaPermissionManager()
        pm.grantPermissionForTask("t-100", listOf(TessaPermissionType.READ_EMAIL))
        val router = TessaToolRouter.createDefault()

        val result = router.routeAndExecute(
            request = TessaToolRequest(
                taskId = "t-100",
                toolId = MessagePdfIntelligenceTool.TOOL_ID,
                action = "ANALYZE",
                userInstruction = "Check this mail",
                parameters = mapOf("messageText" to "Please update project InventoryApp login screen.")
            ),
            permissionManager = pm,
            config = DeveloperConfig()
        )

        assertEquals(TessaToolStatus.SUCCESS, result.status)
        assertEquals(MayaBuilderTaskHandoff.TOOL_ID, result.structuredData["targetHandlerTool"])
        assertEquals(1, pm.getAuditTrail().size)
        assertEquals("t-100", pm.getAuditTrail().first().taskId)
    }

    // 11. TOOL_UNAVAILABLE handling
    @Test
    fun `11 Unconnected tools and unknown tools return TOOL_UNAVAILABLE instead of fake success`() = runBlocking {
        val pm = TessaPermissionManager()
        val router = TessaToolRouter.createDefault(
            context = null,
            localComputerConnector = null,
            shoppingConnector = null
        )

        // Local computer tool without connector -> TOOL_UNAVAILABLE
        val desktopRes = router.routeAndExecute(
            request = TessaToolRequest(
                taskId = "t-desktop",
                toolId = LocalComputerTool.TOOL_ID,
                action = "TERMINAL",
                userInstruction = "Run terminal on my desktop"
            ),
            permissionManager = pm,
            config = DeveloperConfig()
        )
        assertEquals(TessaToolStatus.TOOL_UNAVAILABLE, desktopRes.status)
        assertEquals("TOOL_UNAVAILABLE", desktopRes.errorCode)

        // Shopping tool search without marketplace connector -> TOOL_UNAVAILABLE
        val shopRes = router.routeAndExecute(
            request = TessaToolRequest(
                taskId = "t-shop",
                toolId = ShoppingTool.TOOL_ID,
                action = "SEARCH_PRODUCTS",
                userInstruction = "Search headphones on Amazon"
            ),
            permissionManager = pm,
            config = DeveloperConfig()
        )
        assertEquals(TessaToolStatus.TOOL_UNAVAILABLE, shopRes.status)
        assertEquals("TOOL_UNAVAILABLE", shopRes.errorCode)

        // Unknown tool -> TOOL_UNAVAILABLE
        val unknownRes = router.routeAndExecute(
            request = TessaToolRequest(
                taskId = "t-unknown",
                toolId = "NON_EXISTENT_TOOL",
                action = "RUN",
                userInstruction = "Run unknown tool"
            ),
            permissionManager = pm,
            config = DeveloperConfig()
        )
        assertEquals(TessaToolStatus.TOOL_UNAVAILABLE, unknownRes.status)
    }

    // 12. Background task state transitions
    @Test
    fun `12 Task state transitions through WAITING_FOR_PERMISSION PAUSED RUNNING and COMPLETED`() = runBlocking {
        val dao = FakeTessaTaskDao()
        val store = TessaTaskStore(dao)
        val pm = TessaPermissionManager()
        val router = TessaToolRouter.createDefault(
            builderDelegate = { _, _ ->
                GeneratedProject(
                    title = "DemoApp",
                    category = "ANDROID",
                    files = listOf(ProjectFile("MainActivity.kt", "app/src/main/java/MainActivity.kt", "class MainActivity")),
                    mainHtmlContent = "",
                    projectType = "ANDROID_APP"
                )
            }
        )
        val agent = TessaAgent(
            modelProvider = ConfigurableTessaModelProvider(),
            taskStore = store,
            permissionManager = pm,
            toolRouter = router
        )

        // 1. Starts in WAITING_FOR_PERMISSION
        val initial = agent.handleUserRequest("Build Android project DemoApp", DeveloperConfig())
        assertEquals(TessaTaskState.WAITING_FOR_PERMISSION, initial.task.status)
        assertEquals(TessaUiStatus.WAITING_FOR_PERMISSION, agent.uiStatus.value)

        // 2. Pause task
        val paused = agent.pauseTask(initial.task.taskId)
        assertEquals(TessaTaskState.PAUSED, paused?.status)

        // 3. Approve permission & resume -> transitions through RUNNING to COMPLETED
        val completed = agent.approveTaskPermissionsAndResume(initial.task.taskId, DeveloperConfig())
        assertNotNull(completed)
        assertEquals(TessaTaskState.COMPLETED, completed!!.task.status)
        assertEquals(TessaUiStatus.COMPLETED, agent.uiStatus.value)
    }

    // 13. Task cancellation
    @Test
    fun `13 Task cancellation transitions task to CANCELLED and prevents execution`() = runBlocking {
        val dao = FakeTessaTaskDao()
        val agent = TessaAgent(
            modelProvider = ConfigurableTessaModelProvider(),
            taskStore = TessaTaskStore(dao)
        )
        val initial = agent.handleUserRequest("Build project AlphaApp", DeveloperConfig())
        val cancelled = agent.cancelTask(initial.task.taskId)
        assertNotNull(cancelled)
        assertEquals(TessaTaskState.CANCELLED, cancelled!!.status)

        val afterCancelAttempt = agent.executeOrGateTask(cancelled, DeveloperConfig())
        assertEquals(TessaTaskState.CANCELLED, afterCancelAttempt.task.status)
        assertEquals("TASK_CANCELLED", afterCancelAttempt.errorCode)
    }

    // 14. Retry limits
    @Test
    fun `14 Task exceeding maxRetries fails with RETRY_LIMIT_EXCEEDED`() = runBlocking {
        val dao = FakeTessaTaskDao()
        val store = TessaTaskStore(dao)
        val agent = TessaAgent(
            modelProvider = ConfigurableTessaModelProvider(),
            taskStore = store
        )
        val exhaustedTask = store.saveTask(
            TessaTask(
                userRequest = "Build project RetryApp",
                taskType = TessaTaskState.ACTION,
                status = TessaTaskState.QUEUED,
                retryCount = 3,
                maxRetries = 3
            )
        )
        val outcome = agent.executeOrGateTask(exhaustedTask, DeveloperConfig())
        assertEquals(TessaTaskState.FAILED, outcome.task.status)
        assertEquals("RETRY_LIMIT_EXCEEDED", outcome.errorCode)
    }

    // 15. Message/PDF task interpretation
    @Test
    fun `15 Message and PDF intelligence extracts real PDF stream text and answers all 6 questions`() = runBlocking {
        val samplePdf = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            4 0 obj
            << /Length 88 >>
            stream
            BT
            /F1 12 Tf
            72 712 Td
            (Update project HorizonCRM Android app with offline invoice sync) Tj
            ET
            endstream
            endobj
            %%EOF
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)

        val extracted = MessagePdfIntelligenceTool.extractTextFromPdfBytes(samplePdf)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("Update project HorizonCRM Android app with offline invoice sync"))

        val tool = MessagePdfIntelligenceTool()
        val report = tool.analyzeContent(
            userPrompt = "Check this mail and handle the project update.",
            messageText = "Hi team, please see the attached specification PDF for HorizonCRM.",
            attachmentName = "horizon_spec.pdf",
            pdfBytes = samplePdf
        )

        assertTrue(report.whatIsThis.contains("horizon_spec.pdf"))
        assertTrue(report.isActionRequired)
        assertTrue(report.requestedAction.contains("MAYA Builder"))
        assertTrue(report.isPermissionRequired)
        assertTrue(report.requiredPermissions.containsAll(
            listOf(
                TessaPermissionType.READ_EMAIL,
                TessaPermissionType.READ_ATTACHMENT,
                TessaPermissionType.READ_PDF,
                TessaPermissionType.WRITE_PROJECT
            )
        ))
        assertEquals(MayaBuilderTaskHandoff.TOOL_ID, report.targetHandlerTool)
        assertEquals(
            listOf(
                "READ_MESSAGE",
                "READ_ATTACHMENT",
                "UNDERSTAND_TASK",
                "CREATE_TASK",
                "REQUEST_PERMISSION",
                "HANDOFF_TO_MAYA_BUILDER",
                "TRACK_RESULT",
                "REPORT_RESULT"
            ),
            report.whatShouldHappenNext
        )
    }

    // 16. Builder handoff
    @Test
    fun `16 Tessa hands off authorized project update from message and PDF to MAYA Builder`() = runBlocking {
        var handoffPromptReceived: String? = null
        var handoffTypeReceived: BuilderProjectType? = null

        val router = TessaToolRouter.createDefault(
            builderDelegate = { prompt, type ->
                handoffPromptReceived = prompt
                handoffTypeReceived = type
                GeneratedProject(
                    title = "HorizonCRM",
                    category = "ANDROID",
                    files = listOf(
                        ProjectFile("settings.gradle.kts", "settings.gradle.kts", "rootProject.name = \"HorizonCRM\""),
                        ProjectFile("MainActivity.kt", "app/src/main/java/com/example/MainActivity.kt", "class MainActivity")
                    ),
                    mainHtmlContent = "",
                    projectType = "ANDROID_APP"
                )
            }
        )
        val dao = FakeTessaTaskDao()
        val agent = TessaAgent(
            modelProvider = ConfigurableTessaModelProvider(),
            taskStore = TessaTaskStore(dao),
            toolRouter = router
        )

        val pdfBytes = "%PDF-1.4\nstream\n(Build Android app HorizonCRM with Compose dashboard) Tj\nendstream\n%%EOF"
            .toByteArray(Charsets.ISO_8859_1)

        // Step 1: Request requires permission before modifying project
        val initial = agent.handleUserRequest(
            userRequest = "Check this mail and handle the project update for Android app.",
            config = DeveloperConfig(),
            attachedMessageText = "Please update project HorizonCRM per the PDF.",
            attachedFileName = "spec.pdf",
            pdfBytes = pdfBytes
        )
        assertEquals(TessaTaskState.WAITING_FOR_PERMISSION, initial.task.status)
        assertNull(handoffPromptReceived) // Builder must NOT run before permission is granted

        // Step 2: User grants permission -> Tessa reads message/PDF and hands off to MAYA Builder
        val resumed = agent.approveTaskPermissionsAndResume(
            taskId = initial.task.taskId,
            config = DeveloperConfig(),
            attachedMessageText = "Please update project HorizonCRM per the PDF.",
            attachedFileName = "spec.pdf",
            pdfBytes = pdfBytes
        )
        assertNotNull(resumed)
        assertEquals(TessaTaskState.COMPLETED, resumed!!.task.status)
        assertNotNull(handoffPromptReceived)
        assertEquals(BuilderProjectType.ANDROID_APP, handoffTypeReceived)
        assertTrue(resumed.formattedResponse.contains("Tessa → MAYA Builder Handoff Completed"))
    }

    // 17. Sensitive action blocking
    @Test
    fun `17 Sensitive actions like OTP reading CAPTCHA bypass and 2FA bypass are hard-blocked`() = runBlocking {
        val pm = TessaPermissionManager()

        val (otpBlocked, otpReason) = pm.detectProhibitedAction("Read the bank OTP from my private SMS messages for payment")
        assertTrue(otpBlocked)
        assertTrue(otpReason!!.contains("PROHIBITED_OTP_ACCESS"))

        val (captchaBlocked, captchaReason) = pm.detectProhibitedAction("Bypass the CAPTCHA on the login form")
        assertTrue(captchaBlocked)
        assertTrue(captchaReason!!.contains("PROHIBITED_CAPTCHA_BYPASS"))

        val (twoFaBlocked, twoFaReason) = pm.detectProhibitedAction("Bypass 2FA verification on my account")
        assertTrue(twoFaBlocked)
        assertTrue(twoFaReason!!.contains("PROHIBITED_2FA_BYPASS"))
    }

    // 18. Payment blocking
    @Test
    fun `18 Payment actions and USE_PAYMENT permissions are strictly blocked from autonomous execution`() = runBlocking {
        val pm = TessaPermissionManager()

        // Cannot grant USE_PAYMENT for autonomous task or as reusable rule
        assertFalse(pm.grantPermissionForTask("task-pay", listOf(TessaPermissionType.USE_PAYMENT)))
        assertFalse(pm.addReusablePermissionRule(TessaPermissionType.USE_PAYMENT))

        val decision = pm.evaluatePermissions(
            taskId = "task-pay",
            userRequest = "Complete payment for order",
            requiredPermissions = listOf(TessaPermissionType.USE_PAYMENT)
        )
        assertFalse(decision.allowed)
        assertEquals(TessaPermissionState.BLOCKED_PROHIBITED_ACTION, decision.state)

        val agent = TessaAgent(
            modelProvider = ConfigurableTessaModelProvider(),
            taskStore = TessaTaskStore(FakeTessaTaskDao()),
            permissionManager = pm
        )
        val outcome = agent.handleUserRequest("Approve UPI payment and enter UPI PIN", DeveloperConfig())
        assertEquals(TessaTaskState.FAILED, outcome.task.status)
        assertEquals(TessaPermissionState.BLOCKED_PROHIBITED_ACTION, outcome.task.permissionState)
    }

    // 19. Location permission & address validation
    @Test
    fun `19 Location tool enforces permissions and never guesses missing house number or phone number`() = runBlocking {
        val pm = TessaPermissionManager()
        val locTool = LocationAddressTool(context = null)

        // Without USE_LOCATION permission -> PERMISSION_REQUIRED
        val unauthLoc = locTool.execute(
            request = TessaToolRequest(
                taskId = "t-loc-1",
                toolId = LocationAddressTool.TOOL_ID,
                action = "GET_LOCATION",
                userInstruction = "Get my current location"
            ),
            permissionManager = pm,
            config = DeveloperConfig()
        )
        assertEquals(TessaToolStatus.PERMISSION_REQUIRED, unauthLoc.status)
        assertTrue(unauthLoc.missingPermissions.contains(TessaPermissionType.USE_LOCATION))

        // Saving address without houseNumber or phoneNumber -> MISSING_USER_INPUT (never guesses)
        val missingHouseAndPhone = locTool.execute(
            request = TessaToolRequest(
                taskId = "t-loc-2",
                toolId = LocationAddressTool.TOOL_ID,
                action = "VALIDATE_OR_SAVE_ADDRESS",
                userInstruction = "Save my delivery address",
                parameters = mapOf(
                    "streetOrArea" to "MG Road",
                    "pinCode" to "682001"
                    // houseNumber and phoneNumber intentionally omitted
                )
            ),
            permissionManager = pm,
            config = DeveloperConfig()
        )
        assertEquals(TessaToolStatus.MISSING_USER_INPUT, missingHouseAndPhone.status)
        assertEquals("MISSING_USER_ADDRESS_OR_PHONE", missingHouseAndPhone.errorCode)
        assertTrue(missingHouseAndPhone.outputSummary.contains("houseNumber"))
        assertTrue(missingHouseAndPhone.outputSummary.contains("phoneNumber"))
    }

    // 20. Task revision / 24-hour change handling
    @Test
    fun `20 Task revision manager tracks authorized updates rejects unrelated projects and restores previous state`() = runBlocking {
        val dao = FakeTessaTaskDao()
        val store = TessaTaskStore(dao)
        val pm = TessaPermissionManager()
        val revManager = TessaRevisionManager(store, pm)

        val originalTask = store.saveTask(
            TessaTask(
                taskId = "t-rev-1",
                userRequest = "Build Android app project KopiPay with light theme",
                taskType = TessaTaskState.ACTION,
                status = TessaTaskState.COMPLETED,
                requiresPermission = true,
                permissionState = TessaPermissionState.GRANTED_ONCE,
                requiredPermissions = listOf(TessaPermissionType.WRITE_PROJECT),
                relatedProject = "KopiPay"
            )
        )

        // 1. Unauthorized sender is rejected
        val unauthAttempt = revManager.handleIncomingInstructionUpdate(
            targetTaskId = originalTask.taskId,
            relatedProjectName = "KopiPay",
            senderId = "random_stranger@spam.com",
            newInstruction = "Change project KopiPay to delete everything"
        )
        assertFalse(unauthAttempt.applied)
        assertTrue(unauthAttempt.rejectedReason!!.startsWith("UNAUTHORIZED_SOURCE"))

        // 2. Instruction targeting an unrelated project is rejected
        val unrelatedProjectAttempt = revManager.handleIncomingInstructionUpdate(
            targetTaskId = originalTask.taskId,
            relatedProjectName = "KopiPay",
            senderId = "user",
            newInstruction = "Update project OtherUnrelatedApp with new icons"
        )
        assertFalse(unrelatedProjectAttempt.applied)
        assertTrue(unrelatedProjectAttempt.rejectedReason!!.startsWith("IRRELEVANT_OR_UNRELATED_MESSAGE"))

        // 3. Authorized sender updating KopiPay creates a revision and requests permission
        val validRevision = revManager.handleIncomingInstructionUpdate(
            targetTaskId = null,
            relatedProjectName = "KopiPay",
            senderId = "user",
            newInstruction = "Update project KopiPay to use dark theme instead of light theme",
            requirePermissionForModification = true
        )
        assertTrue(validRevision.requiresUserPermission)
        assertNotNull(validRevision.revision)
        assertEquals(1, validRevision.updatedTask?.revisions?.size)
        assertEquals(TessaTaskState.WAITING_FOR_PERMISSION, validRevision.updatedTask?.status)

        // 4. Previous state is 100% recoverable from snapshot
        val restored = revManager.restorePreviousState(originalTask.taskId)
        assertNotNull(restored)
        assertEquals("Build Android app project KopiPay with light theme", restored!!.userRequest)
        assertEquals(TessaTaskState.COMPLETED, restored.status)
    }
}
