package com.cocateail.maya.tessa

import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.data.provider.LlmRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TessaRuntimeProviderTest {

    private class MockMayaOfflineProvider : MayaOfflineProvider {
        override val providerId: String = "mock_maya_offline"
        var called = false
        override suspend fun generateOffline(request: LlmRequest): String? {
            called = true
            return "Maya Offline Reply"
        }
    }

    private class MockMayaOnlineProvider : MayaOnlineProvider {
        override val providerId: String = "mock_maya_online"
        var called = false
        override suspend fun generateOnline(request: LlmRequest): String {
            called = true
            return "Maya Online Gemini Reply"
        }
    }

    private class MockTessaModelProvider(
        var available: Boolean = true,
        var mockResponseText: String = "Tessa Model Response",
        var returnErrorCode: String? = null,
        var returnErrorMessage: String? = null
    ) : TessaModelProvider {
        override val providerName: String = "MockTessaProvider"
        var lastReceivedRequest: TessaModelRequest? = null
        var testConnectionCallCount = 0

        override fun isAvailable(config: DeveloperConfig): Boolean {
            return config.tessaModelId.isNotBlank() &&
                config.tessaApiBaseUrl.isNotBlank() &&
                config.tessaApiKey.isNotBlank()
        }

        override fun inspectDiagnostics(config: DeveloperConfig): TessaDiagnosticsInfo {
            val isModel = config.tessaModelId.isNotBlank()
            val isBase = config.tessaApiBaseUrl.isNotBlank()
            val isKey = config.tessaApiKey.isNotBlank()
            val isReady = isModel && isBase && isKey
            val status = if (isReady) TessaRuntimeStatus.TESSA_READY else TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED
            return TessaDiagnosticsInfo(
                providerClass = providerName,
                isModelIdConfigured = isModel,
                isEndpointConfigured = isBase,
                isApiKeyConfigured = isKey,
                isProviderReachable = isReady && available,
                isRuntimeReady = isReady,
                runtimeStatus = status,
                statusMessage = if (isReady) "Ready" else "Configuration Required"
            )
        }

        override suspend fun testConnection(config: DeveloperConfig): TessaDiagnosticsInfo {
            testConnectionCallCount++
            val diag = inspectDiagnostics(config)
            return if (available && diag.isRuntimeReady) {
                diag.copy(
                    isProviderReachable = true,
                    isRuntimeReady = true,
                    runtimeStatus = TessaRuntimeStatus.TESSA_READY,
                    statusMessage = "SUCCESS: Provider reachable & authenticated."
                )
            } else {
                diag.copy(
                    isProviderReachable = false,
                    isRuntimeReady = false,
                    runtimeStatus = if (!diag.isRuntimeReady) TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED else TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE,
                    statusMessage = "FAILURE: Provider unavailable"
                )
            }
        }

        override suspend fun generate(request: TessaModelRequest): TessaModelResponse {
            lastReceivedRequest = request
            return if (available) {
                TessaModelResponse(
                    available = true,
                    content = mockResponseText,
                    modelIdUsed = request.tessaModelId,
                    providerEndpointUsed = request.tessaApiBaseUrl
                )
            } else {
                TessaModelResponse(
                    available = false,
                    errorCode = returnErrorCode ?: "TESSA_UNAVAILABLE",
                    errorMessage = returnErrorMessage ?: "Provider is not reachable",
                    modelIdUsed = request.tessaModelId,
                    providerEndpointUsed = request.tessaApiBaseUrl
                )
            }
        }
    }

    // 1. Missing Tessa Model ID diagnostics
    @Test
    fun `test missing Tessa Model ID reports configuration required`() {
        val provider = TessaHttpModelProvider()
        val config = DeveloperConfig(
            tessaModelId = "",
            tessaApiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            tessaApiKey = "test_key_abc"
        )

        val diag = provider.inspectDiagnostics(config)
        assertFalse(diag.isModelIdConfigured)
        assertTrue(diag.isEndpointConfigured)
        assertTrue(diag.isApiKeyConfigured)
        assertFalse(diag.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED, diag.runtimeStatus)
        assertTrue(diag.statusMessage.contains("TESSA_MODEL_ID_MISSING"))
    }

    // 2. Configured Tessa Model ID diagnostics
    @Test
    fun `test configured Tessa Model ID is recognized`() {
        val provider = TessaHttpModelProvider()
        val config = DeveloperConfig(
            tessaModelId = "tessa-deep-planner-v1",
            tessaApiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            tessaApiKey = "secret_valid_key"
        )

        val diag = provider.inspectDiagnostics(config)
        assertTrue(diag.isModelIdConfigured)
        assertTrue(diag.isEndpointConfigured)
        assertTrue(diag.isApiKeyConfigured)
        assertTrue(diag.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_READY, diag.runtimeStatus)
    }

    // 3. Missing API endpoint
    @Test
    fun `test missing API endpoint reports configuration required`() {
        val provider = TessaHttpModelProvider()
        val config = DeveloperConfig(
            tessaModelId = "tessa-model-1",
            tessaApiBaseUrl = "",
            tessaApiKey = "secret_valid_key"
        )

        val diag = provider.inspectDiagnostics(config)
        assertTrue(diag.isModelIdConfigured)
        assertFalse(diag.isEndpointConfigured)
        assertTrue(diag.isApiKeyConfigured)
        assertFalse(diag.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED, diag.runtimeStatus)
        assertTrue(diag.statusMessage.contains("TESSA_ENDPOINT_MISSING"))
    }

    // 4. Missing API key
    @Test
    fun `test missing API key reports configuration required`() {
        val provider = TessaHttpModelProvider()
        val config = DeveloperConfig(
            tessaModelId = "tessa-model-1",
            tessaApiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            tessaApiKey = ""
        )

        val diag = provider.inspectDiagnostics(config)
        assertTrue(diag.isModelIdConfigured)
        assertTrue(diag.isEndpointConfigured)
        assertFalse(diag.isApiKeyConfigured)
        assertFalse(diag.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED, diag.runtimeStatus)
        assertTrue(diag.statusMessage.contains("TESSA_API_KEY_MISSING"))
    }

    // 5. Completely unconfigured provider reports TESSA_PROVIDER_NOT_CONFIGURED
    @Test
    fun `test completely unconfigured provider reports TESSA_PROVIDER_NOT_CONFIGURED`() {
        val provider = TessaHttpModelProvider()
        val config = DeveloperConfig(
            tessaModelId = "",
            tessaApiBaseUrl = "",
            tessaApiKey = ""
        )

        val diag = provider.inspectDiagnostics(config)
        assertFalse(diag.isModelIdConfigured)
        assertFalse(diag.isEndpointConfigured)
        assertFalse(diag.isApiKeyConfigured)
        assertFalse(diag.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED, diag.runtimeStatus)
        assertTrue(diag.statusMessage.contains("TESSA_PROVIDER_NOT_CONFIGURED"))
    }

    // 6. Provider initialization and isAvailable check with default Gemini 3.8 Flash configuration
    @Test
    fun `test provider initialization and availability helper`() {
        val provider = TessaHttpModelProvider()
        val defaultConfigWithoutKey = DeveloperConfig(tessaApiKey = "")
        assertEquals("gemini-3.8-flash", defaultConfigWithoutKey.tessaModelId)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", defaultConfigWithoutKey.tessaApiBaseUrl)
        assertFalse(provider.isAvailable(defaultConfigWithoutKey))

        val readyConfig = DeveloperConfig(
            tessaModelId = "gemini-3.8-flash",
            tessaApiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            tessaApiKey = "ak_123456"
        )
        assertTrue(provider.isAvailable(readyConfig))
    }

    // 7. Provider health check / real connection test with mock server
    @Test
    fun `test provider health check runs real request and never mocks success`() = runBlocking {
        val provider = TessaHttpModelProvider()
        // Default DeveloperConfig() now has tessaModelId, tessaApiBaseUrl, and tessaApiKey configured out of the box
        val defaultAppConfig = DeveloperConfig()
        assertTrue(defaultAppConfig.isTessaProviderConfigured())
        val defaultDiag = provider.inspectDiagnostics(defaultAppConfig)
        assertTrue(defaultDiag.isModelIdConfigured)
        assertTrue(defaultDiag.isEndpointConfigured)
        assertTrue(defaultDiag.isApiKeyConfigured)
        assertTrue(defaultDiag.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_READY, defaultDiag.runtimeStatus)

        // Explicitly blank key must report TESSA_CONFIGURATION_REQUIRED
        val unconfigured = DeveloperConfig(tessaApiKey = "")

        val unconfResult = provider.testConnection(unconfigured)
        assertFalse(unconfResult.isProviderReachable)
        assertFalse(unconfResult.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED, unconfResult.runtimeStatus)

        // Invalid unreachable host must fail and return real failure status, never mock success
        val badHostConfig = DeveloperConfig(
            tessaModelId = "custom-tessa-agent",
            tessaApiBaseUrl = "http://127.0.0.1:59999/unreachable",
            tessaApiKey = "test_key"
        )
        val failResult = provider.testConnection(badHostConfig)
        assertFalse(failResult.isProviderReachable)
        assertFalse(failResult.isRuntimeReady)
        assertEquals(TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE, failResult.runtimeStatus)
        assertTrue(failResult.statusMessage.contains("FAILURE"))
    }

    // 8. TESSA mode startup flow via TessaModelRouter
    @Test
    fun `test TESSA mode startup flow uses TessaModelProvider and delivers response`() = runBlocking {
        val offline = MockMayaOfflineProvider()
        val online = MockMayaOnlineProvider()
        val tessaProvider = MockTessaModelProvider(
            available = true,
            mockResponseText = "Tessa Turbo V1 executed autonomous plan."
        )

        val router = TessaModelRouter(
            mayaOfflineProvider = offline,
            mayaOnlineProvider = online,
            tessaModelProvider = tessaProvider
        )

        val config = DeveloperConfig(
            activeAssistantMode = "TESSA_TURBO_V1",
            tessaModelId = "tessa-turbo-v1-model",
            tessaApiBaseUrl = "https://api.tessa.ai",
            tessaApiKey = "key_valid_123"
        )

        val outcome = router.routeRequest(
            mode = AssistantEngineMode.TESSA_TURBO_V1,
            prompt = "Status update on today's tasks",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = config
        )

        assertEquals(AssistantEngineMode.TESSA_TURBO_V1, outcome.mode)
        assertTrue(outcome.providerAvailable)
        assertEquals("MockTessaProvider", outcome.handledByProvider)
        assertEquals("Tessa Turbo V1 executed autonomous plan.", outcome.replyText)
        assertEquals("tessa-turbo-v1-model", tessaProvider.lastReceivedRequest?.tessaModelId)

        // Verify MAYA providers were NEVER called
        assertFalse(offline.called)
        assertFalse(online.called)
    }

    // 9. Provider separation from MAYA: MAYA Offline uses Qwen, MAYA Online uses Gemini, TESSA uses TessaModelProvider
    @Test
    fun `test strict provider separation between MAYA Offline, MAYA Online, and TESSA`() = runBlocking {
        val offline = MockMayaOfflineProvider()
        val online = MockMayaOnlineProvider()
        val tessa = MockTessaModelProvider()

        val router = TessaModelRouter(
            mayaOfflineProvider = offline,
            mayaOnlineProvider = online,
            tessaModelProvider = tessa
        )
        val config = DeveloperConfig(
            tessaModelId = "tessa-model",
            tessaApiBaseUrl = "https://tessa.ai",
            tessaApiKey = "tessa_key"
        )

        // Route Offline
        val offlineOutcome = router.routeRequest(
            mode = AssistantEngineMode.MAYA_OFFLINE,
            prompt = "Hi Maya",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = config
        )
        assertTrue(offline.called)
        assertFalse(online.called)
        assertNull(tessa.lastReceivedRequest)
        assertEquals("mock_maya_offline", offlineOutcome.handledByProvider)

        // Route Online
        offline.called = false
        val onlineOutcome = router.routeRequest(
            mode = AssistantEngineMode.MAYA_ONLINE,
            prompt = "Hi Maya Online",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = config
        )
        assertFalse(offline.called)
        assertTrue(online.called)
        assertNull(tessa.lastReceivedRequest)
        assertEquals("mock_maya_online", onlineOutcome.handledByProvider)

        // Route Tessa
        online.called = false
        val tessaOutcome = router.routeRequest(
            mode = AssistantEngineMode.TESSA_TURBO_V1,
            prompt = "Deploy app",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = config
        )
        assertFalse(offline.called)
        assertFalse(online.called)
        assertNotNull(tessa.lastReceivedRequest)
        assertEquals("MockTessaProvider", tessaOutcome.handledByProvider)
    }

    // 10. SIMBA Turbo V2 remains completely locked
    @Test
    fun `test SIMBA Turbo V2 remains strictly locked and never calls any provider`() = runBlocking {
        val offline = MockMayaOfflineProvider()
        val online = MockMayaOnlineProvider()
        val tessa = MockTessaModelProvider()

        val router = TessaModelRouter(
            mayaOfflineProvider = offline,
            mayaOnlineProvider = online,
            tessaModelProvider = tessa
        )

        val outcome = router.routeRequest(
            mode = AssistantEngineMode.SIMBA_TURBO_V2,
            prompt = "Start Simba autonomous agent",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = DeveloperConfig()
        )

        assertTrue(outcome.isLocked)
        assertFalse(outcome.providerAvailable)
        assertEquals("SIMBA_TURBO_V2_LOCKED", outcome.errorCode)
        assertTrue(outcome.replyText.contains("Locked"))

        // Zero providers were touched
        assertFalse(offline.called)
        assertFalse(online.called)
        assertNull(tessa.lastReceivedRequest)
    }

    // 11. TESSA does not silently substitute another model if unconfigured
    @Test
    fun `test unconfigured TESSA reports error and never falls back to Maya models`() = runBlocking {
        val offline = MockMayaOfflineProvider()
        val online = MockMayaOnlineProvider()
        val tessa = MockTessaModelProvider(
            available = false,
            returnErrorCode = "TESSA_MODEL_ID_MISSING",
            returnErrorMessage = "tessaModelId not set"
        )

        val router = TessaModelRouter(
            mayaOfflineProvider = offline,
            mayaOnlineProvider = online,
            tessaModelProvider = tessa
        )

        val unconfConfig = DeveloperConfig()
        val outcome = router.routeRequest(
            mode = AssistantEngineMode.TESSA_TURBO_V1,
            prompt = "Hello Tessa",
            history = emptyList(),
            character = MayaCharacter.MAYA,
            config = unconfConfig
        )

        assertFalse(outcome.providerAvailable)
        assertEquals("TESSA_MODEL_ID_MISSING", outcome.errorCode)
        assertTrue(outcome.replyText.contains("TESSA Turbo V1 Provider Unavailable"))

        // Did not fall back to Gemini or Qwen
        assertFalse(offline.called)
        assertFalse(online.called)
    }

    // 12. Real Gemini 3.8 Flash endpoint URL, JSON request body, and x-goog-api-key header construction
    @Test
    fun `test Gemini 3_8 Flash endpoint URL and GenerateContent JSON request construction`() {
        val endpoint = TessaHttpModelProvider.buildEndpointUrl(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelId = "gemini-3.8-flash"
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            endpoint
        )

        val req = TessaModelRequest(
            userPrompt = "Summarize the deployment plan",
            tessaModelId = "gemini-3.8-flash",
            tessaApiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            tessaApiKey = "tessa_dedicated_key_xyz"
        )

        val jsonStr = TessaHttpModelProvider.buildGenerateContentJson(req)
        assertEquals(
            """{"contents":[{"parts":[{"text":"Summarize the deployment plan"}]}]}""",
            jsonStr
        )

        val httpReq = TessaHttpModelProvider.buildHttpRequest(req)
        assertEquals("POST", httpReq.method)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            httpReq.url.toString()
        )
        assertEquals("tessa_dedicated_key_xyz", httpReq.header("x-goog-api-key"))
        assertFalse("Must never leak API key into query string", httpReq.url.toString().contains("tessa_dedicated_key_xyz"))
    }

    private class SimpleTcpHttpServer(
        private val handler: (path: String, method: String, headers: Map<String, String>, body: String) -> Pair<Int, String>
    ) : AutoCloseable {
        private val serverSocket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort

        @Volatile
        private var running = true

        private val acceptThread = Thread {
            while (running && !serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread {
                    socket.use { client ->
                        client.soTimeout = 5000
                        val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
                        val requestLine = input.readLine() ?: return@use
                        val parts = requestLine.split(" ")
                        val method = parts.getOrElse(0) { "" }
                        val path = parts.getOrElse(1) { "" }

                        val headers = mutableMapOf<String, String>()
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            val colonIdx = line.indexOf(':')
                            if (colonIdx > 0) {
                                val k = line.substring(0, colonIdx).trim().lowercase()
                                val v = line.substring(colonIdx + 1).trim()
                                headers[k] = v
                                if (k == "content-length") {
                                    contentLength = v.toIntOrNull() ?: 0
                                }
                            }
                        }

                        val bodyChars = CharArray(contentLength)
                        var readTotal = 0
                        while (readTotal < contentLength) {
                            val r = input.read(bodyChars, readTotal, contentLength - readTotal)
                            if (r == -1) break
                            readTotal += r
                        }
                        val body = String(bodyChars, 0, readTotal)

                        val (status, respBody) = handler(path, method, headers, body)
                        val respBytes = respBody.toByteArray(Charsets.UTF_8)
                        val statusText = when (status) {
                            200 -> "OK"
                            400 -> "Bad Request"
                            401 -> "Unauthorized"
                            403 -> "Forbidden"
                            404 -> "Not Found"
                            429 -> "Too Many Requests"
                            503 -> "Service Unavailable"
                            else -> "Error"
                        }
                        val retryHeader = if (status == 429) "Retry-After: 1\r\n" else ""
                        val headerBytes = (
                            "HTTP/1.1 $status $statusText\r\n" +
                                "Content-Type: application/json\r\n" +
                                retryHeader +
                                "Content-Length: ${respBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.UTF_8)
                        val out = client.getOutputStream()
                        out.write(headerBytes)
                        out.write(respBytes)
                        out.flush()
                    }
                }.start()
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            running = false
            runCatching { serverSocket.close() }
        }
    }

    // 13. Real HTTP POST to /v1beta/models/gemini-3.8-flash:generateContent and Gemini response parsing
    @Test
    fun `test real HTTP POST to gemini-3_8-flash generateContent and end-to-end Tessa routing`() = runBlocking {
        var capturedPath = ""
        var capturedMethod = ""
        var capturedApiKeyHeader = ""
        var capturedRequestBody = ""

        val geminiResponseJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "TESSA Real Gemini 3.8 Flash execution verified."
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        SimpleTcpHttpServer { path, method, headers, body ->
            capturedPath = path
            capturedMethod = method
            capturedApiKeyHeader = headers["x-goog-api-key"].orEmpty()
            capturedRequestBody = body
            200 to geminiResponseJson
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1beta"
            val httpProvider = TessaHttpModelProvider()
            val offlineSpy = MockMayaOfflineProvider()
            val onlineSpy = MockMayaOnlineProvider()
            val agent = TessaAgent(modelProvider = httpProvider)
            val router = TessaModelRouter(
                mayaOfflineProvider = offlineSpy,
                mayaOnlineProvider = onlineSpy,
                tessaModelProvider = httpProvider
            )

            val config = DeveloperConfig(
                activeAssistantMode = "TESSA_TURBO_V1",
                tessaModelId = "gemini-3.8-flash",
                tessaApiBaseUrl = baseUrl,
                tessaApiKey = "secret_tessa_gemini_key_999"
            )

            val outcome = router.routeRequest(
                mode = AssistantEngineMode.TESSA_TURBO_V1,
                prompt = "What is the current status?",
                history = emptyList(),
                character = MayaCharacter.MAYA,
                config = config,
                tessaAgent = agent
            )

            assertTrue(outcome.providerAvailable)
            assertEquals("TESSA Real Gemini 3.8 Flash execution verified.", outcome.replyText)
            assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", capturedPath)
            assertEquals("POST", capturedMethod)
            assertEquals("secret_tessa_gemini_key_999", capturedApiKeyHeader)
            assertEquals("""{"contents":[{"parts":[{"text":"What is the current status?"}]}]}""", capturedRequestBody)

            // Verify diagnostics updated to TESSA_READY with reachable=true and redacted key
            val diag = httpProvider.inspectDiagnostics(config)
            assertEquals(TessaRuntimeStatus.TESSA_READY, diag.runtimeStatus)
            assertTrue(diag.isProviderReachable)
            assertTrue(diag.isRuntimeReady)
            assertEquals("SUCCESS", diag.connectionResult)
            assertEquals(200, diag.httpStatusCode)
            assertFalse(diag.statusMessage.contains("secret_tessa_gemini_key_999"))

            // Strict isolation: MAYA Offline and MAYA Online were never called
            assertFalse(offlineSpy.called)
            assertFalse(onlineSpy.called)
        }
    }

    // 14. Error classification: HTTP 401/403 AUTH_FAILURE, HTTP 404 MODEL_NOT_FOUND, HTTP 429 RATE_LIMIT, and Malformed JSON
    @Test
    fun `test real Gemini HTTP error classification and key redaction`() = runBlocking {
        var statusToReturn = 403
        var bodyToReturn = """{"error":{"code":403,"message":"API key not valid: secret_bad_key_12345","status":"PERMISSION_DENIED"}}"""

        SimpleTcpHttpServer { _, _, _, _ ->
            statusToReturn to bodyToReturn
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1beta"
            val provider = TessaHttpModelProvider()
            val config = DeveloperConfig(
                tessaModelId = "gemini-3.8-flash",
                tessaApiBaseUrl = baseUrl,
                tessaApiKey = "secret_bad_key_12345"
            )

            // 1. 403 Auth failure -> TESSA_AUTH_REQUIRED
            val authDiag = provider.testConnection(config)
            assertEquals(TessaRuntimeStatus.TESSA_AUTH_REQUIRED, authDiag.runtimeStatus)
            assertEquals("AUTH_FAILURE", authDiag.errorCategory)
            assertEquals(403, authDiag.httpStatusCode)
            assertFalse(authDiag.isProviderReachable)
            assertFalse("Must never leak API key in diagnostics", authDiag.statusMessage.contains("secret_bad_key_12345"))

            // 2. 404 Model not found -> TESSA_PROVIDER_UNAVAILABLE
            statusToReturn = 404
            bodyToReturn = """{"error":{"code":404,"message":"models/gemini-3.8-flash is not found","status":"NOT_FOUND"}}"""
            val notFoundDiag = provider.testConnection(config)
            assertEquals(TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE, notFoundDiag.runtimeStatus)
            assertEquals("MODEL_NOT_FOUND", notFoundDiag.errorCategory)
            assertEquals(404, notFoundDiag.httpStatusCode)

            // 3. 429 Quota / rate limit -> TESSA_PROVIDER_UNAVAILABLE
            statusToReturn = 429
            bodyToReturn = """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}"""
            val rateLimitDiag = provider.testConnection(config)
            assertEquals(TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE, rateLimitDiag.runtimeStatus)
            assertEquals("RATE_LIMIT_QUOTA", rateLimitDiag.errorCategory)
            assertEquals(429, rateLimitDiag.httpStatusCode)

            // 4. 200 with malformed/empty candidates -> TESSA_ERROR (MALFORMED_RESPONSE)
            statusToReturn = 200
            bodyToReturn = """{"candidates":[]}"""
            val malformedDiag = provider.testConnection(config)
            assertEquals(TessaRuntimeStatus.TESSA_ERROR, malformedDiag.runtimeStatus)
            assertEquals("MALFORMED_RESPONSE", malformedDiag.errorCategory)
            assertFalse(malformedDiag.isProviderReachable)
        }
    }

    // 15. HTTP 503 bounded retry/backoff: recovers when transient 503 resolves, and bounds retries when persistent
    @Test
    fun `test HTTP 503 bounded retry recovers on transient failure and reports sanitized Gemini error body when persistent`() = runBlocking {
        var requestCount = 0
        var failAllAttempts = false

        val overloadedJson = """
            {
              "error": {
                "code": 503,
                "message": "The model gemini-3.8-flash is currently overloaded. Please try again later.",
                "status": "UNAVAILABLE"
              }
            }
        """.trimIndent()

        val successJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "text": "Recovered after transient HTTP 503." }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        SimpleTcpHttpServer { _, _, _, _ ->
            requestCount++
            if (failAllAttempts || requestCount <= 2) {
                503 to overloadedJson
            } else {
                200 to successJson
            }
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1beta"
            val provider = TessaHttpModelProvider(
                maxTransientRetries = 2,
                initialBackoffMs = 15L
            )
            val config = DeveloperConfig(
                activeAssistantMode = "TESSA_TURBO_V1",
                tessaModelId = "gemini-3.8-flash",
                tessaApiBaseUrl = baseUrl,
                tessaApiKey = "secret_tessa_retry_key_777"
            )

            // Part A: Transient 503 on attempts 1 & 2, succeeds with 200 on attempt 3
            requestCount = 0
            failAllAttempts = false
            val recoveredDiag = provider.testConnection(config)
            assertEquals("Must attempt 3 times (initial + 2 retries)", 3, requestCount)
            assertEquals(TessaRuntimeStatus.TESSA_READY, recoveredDiag.runtimeStatus)
            assertTrue(recoveredDiag.isProviderReachable)
            assertEquals(200, recoveredDiag.httpStatusCode)
            assertEquals(2, recoveredDiag.retryAttempts)
            assertTrue(recoveredDiag.statusMessage.contains("TESSA_READY"))

            // Part B: Persistent 503 across all attempts stops at bounded max (3 attempts total)
            requestCount = 0
            failAllAttempts = true
            val persistentDiag = provider.testConnection(config)
            assertEquals("Must bound 503 retries to maxTransientRetries + 1 (3 attempts)", 3, requestCount)
            assertEquals(TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE, persistentDiag.runtimeStatus)
            assertFalse(persistentDiag.isProviderReachable)
            assertEquals(503, persistentDiag.httpStatusCode)
            assertEquals("HTTP_5XX", persistentDiag.errorCategory)
            assertEquals(2, persistentDiag.retryAttempts)
            assertTrue(
                "Diagnostics must display sanitized Gemini error body",
                persistentDiag.sanitizedErrorBody.contains("UNAVAILABLE: The model gemini-3.8-flash is currently overloaded")
            )
            assertTrue(
                "Status message must include HTTP 503 and sanitized error detail",
                persistentDiag.statusMessage.contains("503") &&
                    persistentDiag.statusMessage.contains("currently overloaded")
            )
        }
    }

    // 16. Do not retry invalid authentication (401/403) or invalid-request (400/404) errors
    @Test
    fun `test invalid authentication and client errors are never retried`() = runBlocking {
        var callCount = 0
        var statusToReturn = 401

        SimpleTcpHttpServer { _, _, _, _ ->
            callCount++
            statusToReturn to """{"error":{"code":$statusToReturn,"message":"Client error $statusToReturn","status":"INVALID_ARGUMENT"}}"""
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1beta"
            val provider = TessaHttpModelProvider(
                maxTransientRetries = 3,
                initialBackoffMs = 10L
            )
            val req = TessaModelRequest(
                userPrompt = "Test non-retry",
                tessaModelId = "gemini-3.8-flash",
                tessaApiBaseUrl = baseUrl,
                tessaApiKey = "test_key_non_retry"
            )

            for (code in listOf(400, 401, 403, 404)) {
                callCount = 0
                statusToReturn = code
                val resp = provider.generate(req)
                assertFalse(resp.available)
                assertEquals("HTTP $code must NOT be retried", 1, callCount)
                assertEquals(0, resp.retryAttempts)
                assertEquals(code, resp.httpStatusCode)
            }
        }
    }

    // 17. Obsolete and invalid model handling without fallback
    @Test
    fun `test obsolete or invalid model is rejected and never falls back to MAYA or Qwen`() = runBlocking {
        val provider = TessaHttpModelProvider()
        val obsoleteModels = listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-pro", "gemini-2.0-flash")

        for (obsolete in obsoleteModels) {
            val cfg = DeveloperConfig(
                tessaModelId = obsolete,
                tessaApiBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
                tessaApiKey = "valid_key_123"
            )
            assertFalse("Obsolete model $obsolete must not be available", provider.isAvailable(cfg))
            val diag = provider.inspectDiagnostics(cfg)
            assertEquals(TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE, diag.runtimeStatus)
            assertEquals("MODEL_NOT_FOUND", diag.errorCategory)
            assertTrue(diag.statusMessage.contains("obsolete"))
        }
    }

    // 18. No API-key leakage in logs, diagnostics, or error messages
    @Test
    fun `test no API key leakage in diagnostics error messages or redactor`() = runBlocking {
        val sensitiveKey = "AQ.Ab8RN6SecretTessaKeyForLeakTest987654"
        val rawLog = "Request failed for key=$sensitiveKey with header x-goog-api-key: $sensitiveKey and token $sensitiveKey"
        val redacted = TessaSecretRedactor.redact(rawLog, listOf(sensitiveKey))

        assertFalse("Must redact raw key", redacted.contains(sensitiveKey))
        assertFalse("Must redact AQ. prefix token", redacted.contains("AQ.Ab8RN6"))

        SimpleTcpHttpServer { _, _, _, _ ->
            503 to """{"error":{"code":503,"message":"Overloaded for key=$sensitiveKey and $sensitiveKey","status":"UNAVAILABLE"}}"""
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1beta"
            val provider = TessaHttpModelProvider(maxTransientRetries = 1, initialBackoffMs = 10L)
            val config = DeveloperConfig(
                activeAssistantMode = "TESSA_TURBO_V1",
                tessaModelId = "gemini-3.8-flash",
                tessaApiBaseUrl = baseUrl,
                tessaApiKey = sensitiveKey
            )

            val diag = provider.testConnection(config)
            assertFalse("statusMessage must not leak key", diag.statusMessage.contains(sensitiveKey))
            assertFalse("sanitizedErrorBody must not leak key", diag.sanitizedErrorBody.contains(sensitiveKey))
            assertFalse("endpointPath must not leak key", diag.endpointPath.contains(sensitiveKey))

            val agent = TessaAgent(modelProvider = provider)
            val outcome = agent.handleUserRequest(
                userRequest = "Check status",
                config = config
            )
            assertFalse("Agent formattedResponse must not leak key", outcome.formattedResponse.contains(sensitiveKey))
            assertFalse("Task error must not leak key", outcome.task.error.orEmpty().contains(sensitiveKey))
        }
    }

    // 19. HTTP 429 transient retry with Retry-After header and recovery
    @Test
    fun `test HTTP 429 transient retry with Retry-After header`() = runBlocking {
        var callCount = 0
        SimpleTcpHttpServer { _, _, _, _ ->
            callCount++
            if (callCount == 1) {
                429 to """{"error":{"code":429,"message":"Resource exhausted quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
            } else {
                200 to """{"candidates":[{"content":{"parts":[{"text":"OK recovered from 429"}]}}]}"""
            }
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1beta"
            val provider = TessaHttpModelProvider(
                maxTransientRetries = 2,
                initialBackoffMs = 10L
            )
            val req = TessaModelRequest(
                userPrompt = "Test 429 retry",
                tessaModelId = "gemini-3.8-flash",
                tessaApiBaseUrl = baseUrl,
                tessaApiKey = "test_key_429"
            )

            val resp = provider.generate(req)
            assertTrue(resp.available)
            assertEquals("OK recovered from 429", resp.content)
            assertEquals(2, callCount)
            assertEquals(1, resp.retryAttempts)
            assertEquals(200, resp.httpStatusCode)
        }
    }

    // 20. HTTP 500 transient retry and recovery
    @Test
    fun `test HTTP 500 transient retry and recovery`() = runBlocking {
        var callCount = 0
        SimpleTcpHttpServer { _, _, _, _ ->
            callCount++
            if (callCount == 1) {
                500 to """{"error":{"code":500,"message":"Internal server error","status":"INTERNAL"}}"""
            } else {
                200 to """{"candidates":[{"content":{"parts":[{"text":"OK recovered from 500"}]}}]}"""
            }
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1beta"
            val provider = TessaHttpModelProvider(
                maxTransientRetries = 2,
                initialBackoffMs = 10L
            )
            val req = TessaModelRequest(
                userPrompt = "Test 500 retry",
                tessaModelId = "gemini-3.8-flash",
                tessaApiBaseUrl = baseUrl,
                tessaApiKey = "test_key_500"
            )

            val resp = provider.generate(req)
            assertTrue(resp.available)
            assertEquals("OK recovered from 500", resp.content)
            assertEquals(2, callCount)
            assertEquals(1, resp.retryAttempts)
        }
    }

    // 21. SocketTimeoutException maps cleanly to TESSA_TIMEOUT errorCategory TIMEOUT
    @Test
    fun `test socket timeout maps cleanly to TESSA_TIMEOUT errorCategory TIMEOUT`() = runBlocking {
        // Fast timeout client
        val fastTimeoutClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        SimpleTcpHttpServer { _, _, _, _ ->
            Thread.sleep(200)
            200 to """{"candidates":[{"content":{"parts":[{"text":"late response"}]}}]}"""
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1beta"
            val provider = TessaHttpModelProvider(
                httpClient = fastTimeoutClient,
                maxTransientRetries = 0
            )
            val req = TessaModelRequest(
                userPrompt = "Test timeout",
                tessaModelId = "gemini-3.8-flash",
                tessaApiBaseUrl = baseUrl,
                tessaApiKey = "test_key_timeout"
            )

            val resp = provider.generate(req)
            assertFalse(resp.available)
            assertEquals("TESSA_TIMEOUT", resp.errorCode)
            assertEquals("TIMEOUT", resp.errorCategory)
            assertNotNull(resp.latencyMs)
            assertTrue(resp.errorMessage?.contains("timed out") == true)
        }
    }

    // 22. False COMPLETED prevention: Failed or unverified operations must never be marked COMPLETED
    @Test
    fun `test false COMPLETED prevention on failed operations`() = runBlocking {
        val failedResult = TessaToolResult(
            toolId = "TEST_TOOL",
            status = TessaToolStatus.FAILED,
            outputSummary = "Compiler error: undeclared identifier 'UGameplayStatics'",
            errorCode = "COMPILER_ERROR",
            structuredData = mapOf("compilerError" to "undeclared identifier 'UGameplayStatics'")
        )

        val decision = TessaVerificationEngine.verifyExecution(failedResult, "Build Project")
        assertNotEquals("Failed tool execution must never be marked PASSED", VerificationStatus.PASSED, decision.status)

        val nonCompilerFailure = TessaToolResult(
            toolId = "TEST_TOOL",
            status = TessaToolStatus.FAILED,
            outputSummary = "Operation crashed with segmentation fault",
            errorCode = "SEGFAULT"
        )
        val decisionFailure = TessaVerificationEngine.verifyExecution(nonCompilerFailure, "Execute Command")
        assertEquals(VerificationStatus.FAILED, decisionFailure.status)
    }

    // 23. Unreal build compiler error repair only marks COMPLETED after real rebuild succeeds
    @Test
    fun `test Unreal build repair only marks COMPLETED after genuine rebuild succeeds`() = runBlocking {
        val failingConnector = object : com.cocateail.maya.tessa.computer.UnrealEngineConnector {
            override fun isAvailable(): Boolean = true
            override suspend fun detectUnreal(): Result<com.cocateail.maya.tessa.computer.UnrealEngineInfo> =
                Result.success(com.cocateail.maya.tessa.computer.UnrealEngineInfo(isInstalled = true))
            override suspend fun createProject(projectName: String, targetFolder: String, engineVersion: String): Result<com.cocateail.maya.tessa.computer.UnrealProjectDescriptor> =
                Result.success(com.cocateail.maya.tessa.computer.UnrealProjectDescriptor(projectName, targetFolder, "$targetFolder/$projectName.uproject"))
            override suspend fun createCppClass(projectName: String, className: String, baseClass: String, isActor: Boolean): Result<Pair<String, String>> =
                Result.success("header.h" to "source.cpp")
            override suspend fun buildProject(projectName: String, projectPath: String, config: String): Result<Pair<Boolean, String>> =
                Result.success(false to "FlightPawn.cpp(15): error C2065: 'UGameplayStatics': undeclared identifier")
            override suspend fun parseBuildErrors(buildLog: String): List<com.cocateail.maya.tessa.computer.UnrealBuildError> =
                listOf(com.cocateail.maya.tessa.computer.UnrealBuildError("FlightPawn.cpp", 15, 1, "C2065", "'UGameplayStatics': undeclared identifier"))
            override suspend fun repairCode(projectName: String, error: com.cocateail.maya.tessa.computer.UnrealBuildError, sourceCode: String): Pair<Boolean, String> =
                true to "#include \"Kismet/GameplayStatics.h\"\n$sourceCode"
            override suspend fun launchProject(projectName: String, projectPath: String): Result<Boolean> = Result.success(false)
        }

        val unrealTool = com.cocateail.maya.tessa.computer.UnrealEngineTool(failingConnector)
        val pm = TessaPermissionManager()
        val config = DeveloperConfig()

        // Grant required permissions for initial build
        pm.grantPermissionForTask("t-unreal-1", listOf(TessaPermissionType.USE_UNREAL, TessaPermissionType.BUILD_PROJECT))

        // 1. Initial build fails
        val buildReq = TessaToolRequest(
            taskId = "t-unreal-1",
            toolId = com.cocateail.maya.tessa.computer.UnrealEngineTool.TOOL_ID,
            action = "BUILD",
            userInstruction = "Build Unreal project FlightSimulator"
        )
        val initialResult = unrealTool.execute(buildReq, pm, config)
        assertEquals(TessaToolStatus.FAILED, initialResult.status)
        assertEquals("COMPILER_ERROR", initialResult.errorCode)

        // 2. Verification confirms repair is required
        val decision = TessaVerificationEngine.verifyExecution(initialResult, "Build Unreal project FlightSimulator")
        assertEquals(VerificationStatus.NEEDS_REPAIR, decision.status)
        assertTrue(decision.repairPayload["compilerError"]!!.contains("UGameplayStatics"))

        // 3. TessaAgent executing repair when rebuild fails must NOT mark COMPLETED
        val fakeModelProvider = MockTessaModelProvider(available = true)
        val customToolRouter = TessaToolRouter(
            tools = listOf(unrealTool)
        )
        val agent = TessaAgent(
            modelProvider = fakeModelProvider,
            permissionManager = pm,
            toolRouter = customToolRouter
        )

        // Grant required permissions
        pm.grantPermissionForTask("t-unreal-test", listOf(TessaPermissionType.USE_UNREAL, TessaPermissionType.BUILD_PROJECT))

        val task = TessaTask(
            taskId = "t-unreal-test",
            userRequest = "Build Unreal project FlightSimulator",
            taskType = TessaTaskState.ACTION,
            status = TessaTaskState.RUNNING,
            requiresPermission = true,
            permissionState = TessaPermissionState.GRANTED_ONCE,
            requiredPermissions = listOf(TessaPermissionType.USE_UNREAL, TessaPermissionType.BUILD_PROJECT),
            targetTool = com.cocateail.maya.tessa.computer.UnrealEngineTool.TOOL_ID,
            relatedProject = "FlightSimulator"
        )

        val outcome = agent.executeOrGateTask(task, config)
        assertNotEquals("Failed rebuild must never result in COMPLETED", TessaTaskState.COMPLETED, outcome.task.status)
        assertEquals(TessaTaskState.FAILED, outcome.task.status)
        assertTrue(outcome.task.error?.contains("Unreal build") == true || outcome.task.error?.contains("failed") == true)
    }

    // 24. Missing computer pairing keeps Computer Agent disconnected and never simulates pairing
    @Test
    fun `test missing computer pairing keeps Computer Agent disconnected`() = runBlocking {
        val connector = com.cocateail.maya.tessa.computer.RealLocalComputerConnector()
        assertFalse("Unpaired connector must not report paired", connector.isPaired())
        assertFalse("Unpaired connector must not report connected", connector.isConnected())
        assertNull("Unpaired connector must have null paired computer", connector.getPairedComputer())

        val failPair = connector.pairComputer(
            computerId = "comp-test",
            deviceName = "Workstation",
            pairingToken = "invalid_token",
            hostAddress = "127.0.0.1",
            port = 59998
        )
        assertTrue(failPair.isFailure)
        assertFalse("Connector must remain disconnected when pairing fails", connector.isConnected())
    }

    // 25. Missing Gmail authorization transitions to WAITING_FOR_USER_AUTHENTICATION
    @Test
    fun `test missing Gmail authorization transitions to WAITING_FOR_USER_AUTHENTICATION`() = runBlocking {
        val disconnectedGmailTool = com.cocateail.maya.tessa.gmail.GmailTool(gmailConnector = null)
        val pm = TessaPermissionManager()
        val config = DeveloperConfig()

        val req = TessaToolRequest(
            taskId = "t-gmail-auth",
            toolId = com.cocateail.maya.tessa.gmail.GmailTool.TOOL_ID,
            action = "LIST_MESSAGES",
            userInstruction = "Check my inbox"
        )

        val toolResult = disconnectedGmailTool.execute(req, pm, config)
        assertEquals(TessaToolStatus.FAILED, toolResult.status)
        assertEquals("WAITING_FOR_USER_AUTHENTICATION", toolResult.errorCode)

        val verification = TessaVerificationEngine.verifyExecution(toolResult, "Check my inbox")
        assertEquals(VerificationStatus.WAITING_FOR_USER_AUTHENTICATION, verification.status)

        // Agent routing
        val customRouter = TessaToolRouter(
            tools = listOf(disconnectedGmailTool)
        )
        val agent = TessaAgent(
            modelProvider = MockTessaModelProvider(available = true),
            permissionManager = pm,
            toolRouter = customRouter
        )

        pm.grantPermissionForTask("t-gmail-auth", listOf(TessaPermissionType.READ_EMAIL))
        val task = TessaTask(
            taskId = "t-gmail-auth",
            userRequest = "Check my inbox",
            taskType = TessaTaskState.TASK,
            status = TessaTaskState.RUNNING,
            requiresPermission = true,
            permissionState = TessaPermissionState.GRANTED_ONCE,
            requiredPermissions = listOf(TessaPermissionType.READ_EMAIL),
            targetTool = com.cocateail.maya.tessa.gmail.GmailTool.TOOL_ID
        )

        val outcome = agent.executeOrGateTask(task, config)
        assertEquals(TessaTaskState.WAITING_FOR_USER_AUTHENTICATION, outcome.task.status)
        assertEquals(TessaUiStatus.WAITING_FOR_AUTHENTICATION, outcome.uiStatus)
        assertTrue(outcome.formattedResponse.contains("Waiting for User Authentication"))
    }
}
