package com.cocateail.maya.tessa

import android.content.Context
import com.cocateail.maya.data.api.GeminiService
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.data.provider.GeminiLlmProvider
import com.cocateail.maya.data.provider.LlmRequest
import com.cocateail.maya.data.provider.LocalGgufLlmProvider
import com.cocateail.maya.data.specialist.SpecialistType
import com.cocateail.maya.data.specialist.TaskClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 1. MAYA Offline Provider — strictly uses Local Qwen2.5-0.5B GGUF via native llama.cpp.
 * Never makes online network calls when MAYA_OFFLINE is selected.
 */
interface MayaOfflineProvider {
    val providerId: String
    suspend fun generateOffline(request: LlmRequest): String?
}

class DefaultMayaOfflineProvider(
    context: Context? = null,
    private val localGgufDelegate: LocalGgufLlmProvider = LocalGgufLlmProvider(context)
) : MayaOfflineProvider {
    override val providerId: String = "maya_offline_qwen_gguf"

    override suspend fun generateOffline(request: LlmRequest): String? {
        return localGgufDelegate.generateResponse(request)
    }
}

/**
 * 2. MAYA Online Provider — uses the existing Gemini online chat pipeline.
 */
interface MayaOnlineProvider {
    val providerId: String
    suspend fun generateOnline(request: LlmRequest): String
}

class DefaultMayaOnlineProvider(
    private val geminiDelegate: GeminiLlmProvider = GeminiLlmProvider()
) : MayaOnlineProvider {
    override val providerId: String = "maya_online_gemini"

    override suspend fun generateOnline(request: LlmRequest): String {
        val direct = geminiDelegate.generateResponse(request)
        if (!direct.isNullOrBlank()) return direct
        return GeminiService.generateResponse(
            prompt = request.prompt,
            history = request.history,
            character = request.character,
            config = request.config,
            imageBitmap = request.imageBitmap,
            replyToId = request.replyToId,
            repliedMessageSender = request.repliedMessageSender,
            repliedMessageText = request.repliedMessageText,
            repliedMessageImageUrl = request.repliedMessageImageUrl
        )
    }
}

/**
 * 3. TESSA Turbo V1 Model Provider — completely separate from MAYA's LlmProvider.
 * Never reuses Maya's model ID automatically and never fakes model execution when unavailable.
 */
@Serializable
data class TessaModelRequest(
    val userPrompt: String,
    val systemPrompt: String = TESSA_DEFAULT_SYSTEM_PROMPT,
    val conversationHistory: List<Pair<String, String>> = emptyList(),
    val attachedDocumentText: String? = null,
    val attachedFileName: String? = null,
    val tessaModelId: String,
    val tessaApiBaseUrl: String,
    val tessaApiKey: String,
    val temperature: Float = 0.2f
) {
    companion object {
        const val TESSA_DEFAULT_SYSTEM_PROMPT =
            "You are TESSA Turbo V1, a permission-controlled task planning and execution assistant. " +
                "Never claim AGI. Always distinguish CHAT, INFORMATION, TASK, and ACTION. " +
                "Never perform external side effects without explicit user permission."
    }
}

@Serializable
data class TessaModelResponse(
    val available: Boolean,
    val content: String = "",
    val modelIdUsed: String = "",
    val providerEndpointUsed: String = "",
    val classifiedTaskState: TessaTaskState = TessaTaskState.TASK,
    val proposedSteps: List<String> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val httpStatusCode: Int? = null,
    val errorCategory: String = "NONE",
    val sanitizedErrorBody: String = "",
    val retryAttempts: Int = 0,
    val latencyMs: Long? = null
)

interface TessaModelProvider {
    val providerName: String
    fun isAvailable(config: DeveloperConfig): Boolean
    fun inspectDiagnostics(config: DeveloperConfig): TessaDiagnosticsInfo
    suspend fun testConnection(config: DeveloperConfig): TessaDiagnosticsInfo
    suspend fun generate(request: TessaModelRequest): TessaModelResponse
}

class TessaHttpModelProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val maxTransientRetries: Int = DEFAULT_MAX_TRANSIENT_RETRIES,
    private val initialBackoffMs: Long = DEFAULT_INITIAL_BACKOFF_MS
) : TessaModelProvider {

    companion object {
        const val DEFAULT_GEMINI_MODEL_ID = "gemini-3.8-flash"
        const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val TESSA_CONNECTION_TEST_PROMPT = "Reply with OK"
        const val EXPECTED_TESSA_CONNECTION_PHRASE = "OK"
        const val DEFAULT_MAX_TRANSIENT_RETRIES = 2
        const val DEFAULT_INITIAL_BACKOFF_MS = 250L

        val sharedInstance: TessaHttpModelProvider by lazy { TessaHttpModelProvider() }

        fun isTransientServerError(statusCode: Int): Boolean {
            return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504
        }

        fun extractSanitizedErrorDetail(bodyStr: String, apiKey: String): String {
            if (bodyStr.isBlank()) return ""
            val rawDetail = try {
                val root = JSONObject(bodyStr)
                when {
                    root.has("error") -> {
                        val errObj = root.optJSONObject("error")
                        if (errObj != null) {
                            val status = errObj.optString("status", "").trim()
                            val message = errObj.optString("message", "").trim()
                            when {
                                status.isNotBlank() && message.isNotBlank() -> "$status: $message"
                                message.isNotBlank() -> message
                                status.isNotBlank() -> status
                                else -> bodyStr.take(300).trim()
                            }
                        } else {
                            root.optString("error", bodyStr.take(300)).trim()
                        }
                    }
                    root.has("message") -> root.optString("message", "").trim()
                    else -> bodyStr.take(300).trim()
                }
            } catch (_: Exception) {
                bodyStr.take(300).trim()
            }
            val singleLine = rawDetail.replace(Regex("\\s+"), " ").trim()
            return TessaSecretRedactor.redact(singleLine, listOf(apiKey))
                .replace(Regex("(?i)key=[^&\\s\"']+"), "key=[REDACTED]")
        }

        fun buildEndpointUrl(baseUrl: String, modelId: String): String {
            val cleanBase = baseUrl.trim().substringBefore("?").trimEnd('/')
            val cleanModel = modelId.trim().removePrefix("models/")
            if (cleanBase.isBlank() || cleanModel.isBlank()) return cleanBase
            return when {
                cleanBase.contains(":generateContent") -> cleanBase
                cleanBase.endsWith("/chat/completions") -> cleanBase
                else -> "$cleanBase/models/$cleanModel:generateContent"
            }
        }

        fun buildGenerateContentJson(request: TessaModelRequest): String {
            val combinedPrompt = buildString {
                append(request.userPrompt)
                if (!request.attachedDocumentText.isNullOrBlank()) {
                    append("\n\n[ATTACHED_DOCUMENT: ${request.attachedFileName ?: "document"}]\n")
                    append(request.attachedDocumentText)
                }
            }
            val partObj = JSONObject().put("text", combinedPrompt)
            val partsArr = JSONArray().put(partObj)
            val contentObj = JSONObject().put("parts", partsArr)
            val contentsArr = JSONArray().put(contentObj)
            return JSONObject().put("contents", contentsArr).toString()
        }

        fun buildHttpRequest(request: TessaModelRequest): Request {
            val modelId = request.tessaModelId.trim()
            val baseUrl = request.tessaApiBaseUrl.trim()
            val apiKey = request.tessaApiKey.trim()
            val endpointUrl = buildEndpointUrl(baseUrl, modelId)

            return if (endpointUrl.endsWith("/chat/completions")) {
                val messagesArr = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", request.systemPrompt))
                    request.conversationHistory.takeLast(6).forEach { (role, text) ->
                        put(
                            JSONObject()
                                .put("role", if (role == "user") "user" else "assistant")
                                .put("content", text)
                        )
                    }
                    put(JSONObject().put("role", "user").put("content", request.userPrompt))
                }
                val payload = JSONObject().apply {
                    put("model", modelId)
                    put("messages", messagesArr)
                    put("temperature", request.temperature.toDouble())
                }
                Request.Builder()
                    .url(endpointUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            } else {
                val jsonBody = buildGenerateContentJson(request)
                Request.Builder()
                    .url(endpointUrl)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
            }
        }

        fun parseGeminiResponseText(bodyStr: String): Result<String> {
            if (bodyStr.isBlank()) {
                return Result.failure(IllegalArgumentException("Empty response body from Gemini API."))
            }
            return try {
                val root = JSONObject(bodyStr)
                when {
                    root.has("candidates") -> {
                        val candidates = root.optJSONArray("candidates")
                        if (candidates == null || candidates.length() == 0) {
                            return Result.failure(IllegalArgumentException("Gemini response missing non-empty candidates array."))
                        }
                        val firstCandidate = candidates.optJSONObject(0)
                            ?: return Result.failure(IllegalArgumentException("Gemini candidate[0] is null."))
                        val contentObj = firstCandidate.optJSONObject("content")
                            ?: return Result.failure(IllegalArgumentException("Gemini candidate[0].content is missing."))
                        val parts = contentObj.optJSONArray("parts")
                        if (parts == null || parts.length() == 0) {
                            return Result.failure(IllegalArgumentException("Gemini candidate[0].content.parts is empty."))
                        }

                        // Support Gemini 3.8 Flash thinking parts: prefer non-thought text parts first
                        val nonThoughtTexts = mutableListOf<String>()
                        val allTexts = mutableListOf<String>()
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val text = part.optString("text", "").trim()
                            if (text.isNotEmpty()) {
                                allTexts.add(text)
                                if (!part.optBoolean("thought", false)) {
                                    nonThoughtTexts.add(text)
                                }
                            }
                        }
                        val extracted = (if (nonThoughtTexts.isNotEmpty()) nonThoughtTexts else allTexts)
                            .joinToString("\n")
                            .trim()
                        if (extracted.isBlank()) {
                            Result.failure(IllegalArgumentException("Gemini response parts contained no usable text."))
                        } else {
                            Result.success(extracted)
                        }
                    }
                    root.has("choices") -> {
                        val text = root.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "")
                            ?.trim()
                            .orEmpty()
                        if (text.isBlank()) {
                            Result.failure(IllegalArgumentException("Provider choices[0].message.content was blank."))
                        } else {
                            Result.success(text)
                        }
                    }
                    else -> {
                        Result.failure(IllegalArgumentException("Malformed response: missing 'candidates' field."))
                    }
                }
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException("Invalid JSON in Gemini response: ${e.message}", e))
            }
        }
    }

    override val providerName: String = "TessaDedicatedHttpProvider"

    @Volatile
    private var lastVerifiedSignature: String? = null

    @Volatile
    private var lastVerifiedDiagnostics: TessaDiagnosticsInfo? = null

    private fun configSignature(modelId: String, baseUrl: String, apiKey: String): String {
        return "$modelId|$baseUrl|${apiKey.hashCode()}"
    }

    override fun isAvailable(config: DeveloperConfig): Boolean {
        val modelId = config.tessaModelId.trim()
        val baseUrl = config.tessaApiBaseUrl.trim()
        val apiKey = config.resolvedTessaApiKey()
        return modelId.isNotBlank() &&
            !DeveloperConfig.isObsoleteGeminiModel(modelId) &&
            baseUrl.isNotBlank() &&
            (baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) &&
            apiKey.isNotBlank()
    }

    override fun inspectDiagnostics(config: DeveloperConfig): TessaDiagnosticsInfo {
        val modelId = config.tessaModelId.trim()
        val baseUrl = config.tessaApiBaseUrl.trim()
        val apiKey = config.resolvedTessaApiKey()
        val safeBaseUrl = TessaSecretRedactor.redact(baseUrl.substringBefore("?"), listOf(apiKey))
        val safeEndpoint = TessaSecretRedactor.redact(buildEndpointUrl(baseUrl, modelId), listOf(apiKey))

        val isModelConfigured = modelId.isNotBlank()
        val isEndpointConfigured = baseUrl.isNotBlank() && (baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))
        val isApiKeyConfigured = apiKey.isNotBlank()

        if (!isModelConfigured && !isEndpointConfigured && !isApiKeyConfigured) {
            return TessaDiagnosticsInfo(
                providerClass = this::class.java.simpleName,
                modelId = modelId,
                baseUrl = safeBaseUrl,
                endpointPath = safeEndpoint,
                isModelIdConfigured = false,
                isEndpointConfigured = false,
                isApiKeyConfigured = false,
                isProviderReachable = false,
                isRuntimeReady = false,
                runtimeStatus = TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED,
                statusMessage = "TESSA_PROVIDER_NOT_CONFIGURED: Tessa Model ID, API Base URL, and API Key are not configured. Open Developer Controls to configure.",
                connectionResult = "NOT_CONFIGURED",
                errorCategory = "PROVIDER_NOT_CONFIGURED",
                lastTestedTimestamp = 0L
            )
        }
        if (!isModelConfigured) {
            return TessaDiagnosticsInfo(
                providerClass = this::class.java.simpleName,
                modelId = modelId,
                baseUrl = safeBaseUrl,
                endpointPath = safeEndpoint,
                isModelIdConfigured = false,
                isEndpointConfigured = isEndpointConfigured,
                isApiKeyConfigured = isApiKeyConfigured,
                isProviderReachable = false,
                isRuntimeReady = false,
                runtimeStatus = TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED,
                statusMessage = "TESSA_MODEL_ID_MISSING: Tessa Model ID (tessaModelId) is not set. Open Developer Controls to configure.",
                connectionResult = "NOT_CONFIGURED",
                errorCategory = "MISSING_MODEL_ID",
                lastTestedTimestamp = 0L
            )
        }
        if (DeveloperConfig.isObsoleteGeminiModel(modelId)) {
            return TessaDiagnosticsInfo(
                providerClass = this::class.java.simpleName,
                modelId = modelId,
                baseUrl = safeBaseUrl,
                endpointPath = safeEndpoint,
                isModelIdConfigured = false,
                isEndpointConfigured = isEndpointConfigured,
                isApiKeyConfigured = isApiKeyConfigured,
                isProviderReachable = false,
                isRuntimeReady = false,
                runtimeStatus = TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE,
                statusMessage = "TESSA_MODEL_NOT_FOUND: Model '$modelId' is obsolete and unsupported. Use '$DEFAULT_GEMINI_MODEL_ID'.",
                connectionResult = "INVALID_MODEL",
                errorCategory = "MODEL_NOT_FOUND",
                sanitizedErrorBody = "Model '$modelId' is obsolete; use '$DEFAULT_GEMINI_MODEL_ID'.",
                lastTestedTimestamp = 0L
            )
        }
        if (!isEndpointConfigured) {
            return TessaDiagnosticsInfo(
                providerClass = this::class.java.simpleName,
                modelId = modelId,
                baseUrl = safeBaseUrl,
                endpointPath = safeEndpoint,
                isModelIdConfigured = true,
                isEndpointConfigured = false,
                isApiKeyConfigured = isApiKeyConfigured,
                isProviderReachable = false,
                isRuntimeReady = false,
                runtimeStatus = TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED,
                statusMessage = "TESSA_ENDPOINT_MISSING: Tessa API Base URL (tessaApiBaseUrl) is not set or invalid (must start with http:// or https://).",
                connectionResult = "NOT_CONFIGURED",
                errorCategory = "MISSING_ENDPOINT",
                lastTestedTimestamp = 0L
            )
        }
        if (!isApiKeyConfigured) {
            return TessaDiagnosticsInfo(
                providerClass = this::class.java.simpleName,
                modelId = modelId,
                baseUrl = safeBaseUrl,
                endpointPath = safeEndpoint,
                isModelIdConfigured = true,
                isEndpointConfigured = true,
                isApiKeyConfigured = false,
                isProviderReachable = false,
                isRuntimeReady = false,
                runtimeStatus = TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED,
                statusMessage = "TESSA_API_KEY_MISSING: Tessa API Key (tessaApiKey) is not set.",
                connectionResult = "NOT_CONFIGURED",
                errorCategory = "MISSING_API_KEY",
                lastTestedTimestamp = 0L
            )
        }

        val sig = configSignature(modelId, baseUrl, apiKey)
        val cached = lastVerifiedDiagnostics
        if (lastVerifiedSignature == sig && cached != null) {
            return cached
        }

        return TessaDiagnosticsInfo(
            providerClass = this::class.java.simpleName,
            modelId = modelId,
            baseUrl = safeBaseUrl,
            endpointPath = safeEndpoint,
            isModelIdConfigured = true,
            isEndpointConfigured = true,
            isApiKeyConfigured = true,
            isProviderReachable = false,
            isRuntimeReady = true,
            runtimeStatus = TessaRuntimeStatus.TESSA_READY,
            statusMessage = "Configured for model '$modelId' ($safeEndpoint). Run 'Test TESSA Connection' to verify live Gemini API reachability.",
            connectionResult = "NOT_TESTED",
            errorCategory = "NONE",
            lastTestedTimestamp = 0L
        )
    }

    override suspend fun testConnection(config: DeveloperConfig): TessaDiagnosticsInfo = withContext(Dispatchers.IO) {
        val modelId = config.tessaModelId.trim()
        val baseUrl = config.tessaApiBaseUrl.trim()
        val apiKey = config.resolvedTessaApiKey()
        val safeBaseUrl = TessaSecretRedactor.redact(baseUrl.substringBefore("?"), listOf(apiKey))
        val safeEndpoint = TessaSecretRedactor.redact(buildEndpointUrl(baseUrl, modelId), listOf(apiKey))

        val staticDiag = inspectDiagnostics(config)
        if (staticDiag.runtimeStatus == TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED ||
            DeveloperConfig.isObsoleteGeminiModel(modelId)
        ) {
            return@withContext staticDiag.copy(lastTestedTimestamp = System.currentTimeMillis())
        }

        val testReq = TessaModelRequest(
            userPrompt = TESSA_CONNECTION_TEST_PROMPT,
            tessaModelId = modelId,
            tessaApiBaseUrl = baseUrl,
            tessaApiKey = apiKey
        )

        val response = generate(testReq)
        val now = System.currentTimeMillis()

        if (response.available && response.content.isNotBlank()) {
            val verifiedDiag = TessaDiagnosticsInfo(
                providerClass = this::class.java.simpleName,
                modelId = modelId,
                baseUrl = safeBaseUrl,
                endpointPath = safeEndpoint,
                isModelIdConfigured = true,
                isEndpointConfigured = true,
                isApiKeyConfigured = true,
                isProviderReachable = true,
                isRuntimeReady = true,
                runtimeStatus = TessaRuntimeStatus.TESSA_READY,
                statusMessage = "TESSA_READY — Live Gemini API connection verified (HTTP ${response.httpStatusCode ?: 200}, ${response.latencyMs ?: 0L}ms). Response: ${
                    TessaSecretRedactor.redact(response.content.take(120), listOf(apiKey))
                }",
                connectionResult = "SUCCESS",
                httpStatusCode = response.httpStatusCode ?: 200,
                errorCategory = "NONE",
                sanitizedErrorBody = "",
                retryAttempts = response.retryAttempts,
                latencyMs = response.latencyMs,
                lastTestedTimestamp = now
            )
            lastVerifiedSignature = configSignature(modelId, baseUrl, apiKey)
            lastVerifiedDiagnostics = verifiedDiag
            return@withContext verifiedDiag
        }

        val errCode = response.errorCode.orEmpty()
        val errCategory = response.errorCategory
        val errMsg = TessaSecretRedactor.redact(response.errorMessage ?: "Unknown error", listOf(apiKey))
        val sanitizedBody = TessaSecretRedactor.redact(response.sanitizedErrorBody, listOf(apiKey))

        val runtimeStatus = when (errCategory) {
            "AUTH_FAILURE" -> TessaRuntimeStatus.TESSA_AUTH_REQUIRED
            "MODEL_NOT_FOUND", "RATE_LIMIT_QUOTA", "HTTP_5XX", "NETWORK_UNAVAILABLE", "TIMEOUT" ->
                TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE
            "MISSING_MODEL_ID", "MISSING_API_KEY", "MISSING_ENDPOINT", "PROVIDER_NOT_CONFIGURED" ->
                TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED
            else -> TessaRuntimeStatus.TESSA_ERROR
        }

        val failedDiag = TessaDiagnosticsInfo(
            providerClass = this::class.java.simpleName,
            modelId = modelId,
            baseUrl = safeBaseUrl,
            endpointPath = safeEndpoint,
            isModelIdConfigured = true,
            isEndpointConfigured = true,
            isApiKeyConfigured = true,
            isProviderReachable = false,
            isRuntimeReady = false,
            runtimeStatus = runtimeStatus,
            statusMessage = "FAILURE ($errCode): $errMsg",
            connectionResult = "FAILURE",
            httpStatusCode = response.httpStatusCode,
            errorCategory = errCategory,
            sanitizedErrorBody = sanitizedBody,
            retryAttempts = response.retryAttempts,
            latencyMs = response.latencyMs,
            lastTestedTimestamp = now
        )
        lastVerifiedSignature = configSignature(modelId, baseUrl, apiKey)
        lastVerifiedDiagnostics = failedDiag
        failedDiag
    }

    override suspend fun generate(request: TessaModelRequest): TessaModelResponse = withContext(Dispatchers.IO) {
        val modelId = request.tessaModelId.trim()
        val baseUrl = request.tessaApiBaseUrl.trim()
        val apiKey = request.tessaApiKey.trim()
        val safeBaseUrl = TessaSecretRedactor.redact(baseUrl.substringBefore("?"), listOf(apiKey))
        val safeEndpoint = TessaSecretRedactor.redact(buildEndpointUrl(baseUrl, modelId), listOf(apiKey))

        if (modelId.isBlank()) {
            return@withContext TessaModelResponse(
                available = false,
                errorCode = "TESSA_MODEL_ID_MISSING",
                errorCategory = "MISSING_MODEL_ID",
                errorMessage = "TESSA Turbo V1 model ID (tessaModelId) is not configured in DeveloperConfig."
            )
        }
        if (DeveloperConfig.isObsoleteGeminiModel(modelId)) {
            return@withContext TessaModelResponse(
                available = false,
                modelIdUsed = modelId,
                providerEndpointUsed = safeEndpoint,
                errorCode = "TESSA_MODEL_NOT_FOUND",
                errorCategory = "MODEL_NOT_FOUND",
                sanitizedErrorBody = "Model '$modelId' is obsolete and prohibited. Use '$DEFAULT_GEMINI_MODEL_ID'.",
                errorMessage = "Model '$modelId' is obsolete and unsupported. Use '$DEFAULT_GEMINI_MODEL_ID'."
            )
        }
        if (baseUrl.isBlank() || (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://"))) {
            return@withContext TessaModelResponse(
                available = false,
                modelIdUsed = modelId,
                errorCode = "TESSA_ENDPOINT_MISSING",
                errorCategory = "MISSING_ENDPOINT",
                errorMessage = "TESSA Turbo V1 API base URL (tessaApiBaseUrl) is not configured or invalid."
            )
        }
        if (apiKey.isBlank()) {
            return@withContext TessaModelResponse(
                available = false,
                modelIdUsed = modelId,
                providerEndpointUsed = safeEndpoint,
                errorCode = "TESSA_API_KEY_MISSING",
                errorCategory = "MISSING_API_KEY",
                errorMessage = "TESSA Turbo V1 API key (tessaApiKey) is not configured."
            )
        }

        val startTimeMs = System.currentTimeMillis()
        var retriesUsed = 0
        try {
            val httpRequest = buildHttpRequest(request)
            var statusCode = 0
            var bodyStr = ""

            val maxAttempts = (maxTransientRetries.coerceAtLeast(0)) + 1
            for (attempt in 0 until maxAttempts) {
                var retryAfterMs: Long? = null
                httpClient.newCall(httpRequest).execute().use { response ->
                    statusCode = response.code
                    bodyStr = response.body?.string().orEmpty()
                    val retryHeader = response.header("Retry-After")
                    if (!retryHeader.isNullOrBlank()) {
                        val parsedSec = retryHeader.trim().toLongOrNull()
                        if (parsedSec != null && parsedSec > 0) {
                            retryAfterMs = (parsedSec * 1000L).coerceIn(50L, 5000L)
                        }
                    }
                }

                if (statusCode in 200..299) {
                    break
                }

                // Only retry on transient failures (HTTP 429, 500, 502, 503, 504) up to maxTransientRetries.
                // Respect Retry-After where supported, bounded to 5000ms.
                // Never retry authentication (401/403), invalid-request (400), or model-not-found (404) errors.
                if (isTransientServerError(statusCode) && attempt < maxAttempts - 1) {
                    retriesUsed = attempt + 1
                    val baseBackoff = (initialBackoffMs.coerceAtLeast(10L)) * (1L shl attempt)
                    val backoffDelayMs = (retryAfterMs ?: baseBackoff).coerceIn(10L, 5000L)
                    kotlinx.coroutines.delay(backoffDelayMs)
                } else {
                    break
                }
            }

            val latencyMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)

            if (statusCode !in 200..299) {
                val sanitizedDetail = extractSanitizedErrorDetail(bodyStr, apiKey)
                val lowerBody = sanitizedDetail.lowercase()
                val detailSuffix = if (sanitizedDetail.isNotBlank()) " — $sanitizedDetail" else ""

                val (errCode, errCategory, errMessage) = when {
                    statusCode == 401 || statusCode == 403 ||
                        lowerBody.contains("api_key_invalid") ||
                        lowerBody.contains("api key not valid") ||
                        lowerBody.contains("unauthenticated") ||
                        lowerBody.contains("permission_denied") -> {
                        Triple(
                            "TESSA_AUTH_FAILURE",
                            "AUTH_FAILURE",
                            "Authentication failed (HTTP $statusCode): Invalid or unauthorized TESSA API key$detailSuffix"
                        )
                    }
                    statusCode == 404 || lowerBody.contains("not_found") || lowerBody.contains("is not found for api version") -> {
                        Triple(
                            "TESSA_MODEL_NOT_FOUND",
                            "MODEL_NOT_FOUND",
                            "Model '$modelId' not found at endpoint (HTTP $statusCode)$detailSuffix"
                        )
                    }
                    statusCode == 429 || lowerBody.contains("resource_exhausted") || lowerBody.contains("quota") || lowerBody.contains("rate limit") -> {
                        Triple(
                            "TESSA_RATE_LIMIT_EXCEEDED",
                            "RATE_LIMIT_QUOTA",
                            "Gemini API rate limit or quota exceeded (HTTP $statusCode)$detailSuffix"
                        )
                    }
                    statusCode in 400..499 -> {
                        Triple(
                            "TESSA_HTTP_$statusCode",
                            "HTTP_4XX",
                            "Gemini API rejected the request with client error HTTP $statusCode$detailSuffix"
                        )
                    }
                    statusCode in 500..599 -> {
                        val retryNote = if (retriesUsed > 0) " (after ${retriesUsed + 1} attempts)" else ""
                        Triple(
                            "TESSA_HTTP_$statusCode",
                            "HTTP_5XX",
                            "Gemini API server error HTTP $statusCode$retryNote$detailSuffix"
                        )
                    }
                    else -> {
                        Triple(
                            "TESSA_HTTP_$statusCode",
                            "HTTP_ERROR",
                            "Tessa model provider returned HTTP $statusCode$detailSuffix"
                        )
                    }
                }

                val runtimeStatus = when (errCategory) {
                    "AUTH_FAILURE" -> TessaRuntimeStatus.TESSA_AUTH_REQUIRED
                    "MODEL_NOT_FOUND", "RATE_LIMIT_QUOTA", "HTTP_5XX", "NETWORK_UNAVAILABLE", "TIMEOUT" ->
                        TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE
                    else -> TessaRuntimeStatus.TESSA_ERROR
                }

                lastVerifiedSignature = configSignature(modelId, baseUrl, apiKey)
                lastVerifiedDiagnostics = TessaDiagnosticsInfo(
                    providerClass = this::class.java.simpleName,
                    modelId = modelId,
                    baseUrl = safeBaseUrl,
                    endpointPath = safeEndpoint,
                    isModelIdConfigured = true,
                    isEndpointConfigured = true,
                    isApiKeyConfigured = true,
                    isProviderReachable = false,
                    isRuntimeReady = false,
                    runtimeStatus = runtimeStatus,
                    statusMessage = "FAILURE ($errCode): $errMessage",
                    connectionResult = "FAILURE",
                    httpStatusCode = statusCode,
                    errorCategory = errCategory,
                    sanitizedErrorBody = sanitizedDetail,
                    retryAttempts = retriesUsed,
                    latencyMs = latencyMs,
                    lastTestedTimestamp = System.currentTimeMillis()
                )

                return@withContext TessaModelResponse(
                    available = false,
                    modelIdUsed = modelId,
                    providerEndpointUsed = safeEndpoint,
                    errorCode = errCode,
                    errorCategory = errCategory,
                    errorMessage = errMessage,
                    httpStatusCode = statusCode,
                    sanitizedErrorBody = sanitizedDetail,
                    retryAttempts = retriesUsed,
                    latencyMs = latencyMs
                )
            }

            val parsedResult = parseGeminiResponseText(bodyStr)
            if (parsedResult.isFailure) {
                val reason = TessaSecretRedactor.redact(
                    parsedResult.exceptionOrNull()?.message ?: "Malformed Gemini JSON payload.",
                    listOf(apiKey)
                )
                lastVerifiedSignature = configSignature(modelId, baseUrl, apiKey)
                lastVerifiedDiagnostics = TessaDiagnosticsInfo(
                    providerClass = this::class.java.simpleName,
                    modelId = modelId,
                    baseUrl = safeBaseUrl,
                    endpointPath = safeEndpoint,
                    isModelIdConfigured = true,
                    isEndpointConfigured = true,
                    isApiKeyConfigured = true,
                    isProviderReachable = false,
                    isRuntimeReady = false,
                    runtimeStatus = TessaRuntimeStatus.TESSA_ERROR,
                    statusMessage = "FAILURE (TESSA_MALFORMED_RESPONSE): Malformed Gemini response: $reason",
                    connectionResult = "FAILURE",
                    httpStatusCode = statusCode,
                    errorCategory = "MALFORMED_RESPONSE",
                    sanitizedErrorBody = reason,
                    retryAttempts = retriesUsed,
                    latencyMs = latencyMs,
                    lastTestedTimestamp = System.currentTimeMillis()
                )
                return@withContext TessaModelResponse(
                    available = false,
                    modelIdUsed = modelId,
                    providerEndpointUsed = safeEndpoint,
                    errorCode = "TESSA_MALFORMED_RESPONSE",
                    errorCategory = "MALFORMED_RESPONSE",
                    errorMessage = "Malformed Gemini response: $reason",
                    httpStatusCode = statusCode,
                    sanitizedErrorBody = reason,
                    retryAttempts = retriesUsed,
                    latencyMs = latencyMs
                )
            }

            val extractedText = parsedResult.getOrNull().orEmpty()
            val verifiedDiag = TessaDiagnosticsInfo(
                providerClass = this::class.java.simpleName,
                modelId = modelId,
                baseUrl = safeBaseUrl,
                endpointPath = safeEndpoint,
                isModelIdConfigured = true,
                isEndpointConfigured = true,
                isApiKeyConfigured = true,
                isProviderReachable = true,
                isRuntimeReady = true,
                runtimeStatus = TessaRuntimeStatus.TESSA_READY,
                statusMessage = "TESSA_READY — Real Gemini API responded in ${latencyMs}ms (HTTP $statusCode).",
                connectionResult = "SUCCESS",
                httpStatusCode = statusCode,
                errorCategory = "NONE",
                sanitizedErrorBody = "",
                retryAttempts = retriesUsed,
                latencyMs = latencyMs,
                lastTestedTimestamp = System.currentTimeMillis()
            )
            lastVerifiedSignature = configSignature(modelId, baseUrl, apiKey)
            lastVerifiedDiagnostics = verifiedDiag

            return@withContext TessaModelResponse(
                available = true,
                content = extractedText,
                modelIdUsed = modelId,
                providerEndpointUsed = safeEndpoint,
                httpStatusCode = statusCode,
                errorCategory = "NONE",
                sanitizedErrorBody = "",
                retryAttempts = retriesUsed,
                latencyMs = latencyMs
            )
        } catch (e: java.net.SocketTimeoutException) {
            val latencyMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
            val errMsg = TessaSecretRedactor.redact(
                "Tessa Gemini request timed out: ${e.localizedMessage ?: "timeout"}",
                listOf(apiKey)
            ).replace(Regex("(?i)key=[^&\\s\"']+"), "key=[REDACTED]")
            return@withContext TessaModelResponse(
                available = false,
                modelIdUsed = modelId,
                providerEndpointUsed = safeEndpoint,
                errorCode = "TESSA_TIMEOUT",
                errorCategory = "TIMEOUT",
                errorMessage = errMsg,
                sanitizedErrorBody = errMsg,
                retryAttempts = retriesUsed,
                latencyMs = latencyMs
            )
        } catch (e: java.io.InterruptedIOException) {
            val latencyMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
            val errMsg = TessaSecretRedactor.redact(
                "Tessa Gemini request timed out: ${e.localizedMessage ?: "interrupted"}",
                listOf(apiKey)
            ).replace(Regex("(?i)key=[^&\\s\"']+"), "key=[REDACTED]")
            return@withContext TessaModelResponse(
                available = false,
                modelIdUsed = modelId,
                providerEndpointUsed = safeEndpoint,
                errorCode = "TESSA_TIMEOUT",
                errorCategory = "TIMEOUT",
                errorMessage = errMsg,
                sanitizedErrorBody = errMsg,
                retryAttempts = retriesUsed,
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
            val errMsg = TessaSecretRedactor.redact(
                "Tessa model network failure: ${e.localizedMessage ?: "unreachable"}",
                listOf(apiKey)
            ).replace(Regex("(?i)key=[^&\\s\"']+"), "key=[REDACTED]")
            return@withContext TessaModelResponse(
                available = false,
                modelIdUsed = modelId,
                providerEndpointUsed = safeEndpoint,
                errorCode = "TESSA_NETWORK_UNAVAILABLE",
                errorCategory = "NETWORK_UNAVAILABLE",
                errorMessage = errMsg,
                sanitizedErrorBody = errMsg,
                retryAttempts = retriesUsed,
                latencyMs = latencyMs
            )
        }
    }
}

/**
 * 4. SIMBA Turbo V2 Provider Interface — LOCKED.
 * Must NOT be implemented yet. Future SIMBA models can implement this interface
 * without rewriting MAYA or TESSA.
 */
interface FutureSimbaProvider {
    val isLocked: Boolean
        get() = true
}

data class ModeRoutingOutcome(
    val mode: AssistantEngineMode,
    val handledByProvider: String,
    val isLocked: Boolean = false,
    val providerAvailable: Boolean = true,
    val replyText: String,
    val tessaTask: TessaTask? = null,
    val errorCode: String? = null
)

/**
 * Routes requests strictly according to the selected AssistantEngineMode:
 * - MAYA_OFFLINE -> MayaOfflineProvider (Local Qwen2.5-0.5B GGUF)
 * - MAYA_ONLINE -> MayaOnlineProvider (Gemini Online Chat)
 * - TESSA_TURBO_V1 -> TessaAgent (which uses TessaModelProvider + TessaToolRouter + TessaPermissionManager)
 * - SIMBA_TURBO_V2 -> LOCKED (does NOT start an AI model, does NOT execute tools, does NOT execute background tasks)
 */
class TessaModelRouter(
    private val mayaOfflineProvider: MayaOfflineProvider,
    private val mayaOnlineProvider: MayaOnlineProvider,
    private val tessaModelProvider: TessaModelProvider
) {
    fun getTessaModelProvider(): TessaModelProvider = tessaModelProvider

    suspend fun routeRequest(
        mode: AssistantEngineMode,
        prompt: String,
        history: List<Pair<String, String>>,
        character: MayaCharacter,
        config: DeveloperConfig,
        tessaAgent: TessaAgent? = null
    ): ModeRoutingOutcome {
        return when (mode) {
            AssistantEngineMode.SIMBA_TURBO_V2 -> {
                // Hard lock: never start an AI model, never execute tools, never start background tasks
                ModeRoutingOutcome(
                    mode = AssistantEngineMode.SIMBA_TURBO_V2,
                    handledByProvider = "SIMBA_LOCKED_GATE",
                    isLocked = true,
                    providerAvailable = false,
                    replyText = "🔒 **SIMBA Turbo V2 is Locked**\n\nSIMBA Turbo V2 is reserved for a future major update. No AI model, tools, or background tasks are executed in this mode. Switch to **MAYA Offline**, **MAYA Online**, or **TESSA Turbo V1** to continue.",
                    errorCode = "SIMBA_TURBO_V2_LOCKED"
                )
            }

            AssistantEngineMode.MAYA_OFFLINE -> {
                val specialistType = if (config.autoDetectSpecialist) {
                    TaskClassifier.classify(prompt)
                } else {
                    config.selectedSpecialist
                }
                val llmReq = LlmRequest(
                    prompt = prompt,
                    history = history,
                    character = character,
                    config = config.copy(onlineFeaturesEnabled = false),
                    specialistType = specialistType
                )
                val offlineReply = mayaOfflineProvider.generateOffline(llmReq)
                if (!offlineReply.isNullOrBlank()) {
                    ModeRoutingOutcome(
                        mode = AssistantEngineMode.MAYA_OFFLINE,
                        handledByProvider = mayaOfflineProvider.providerId,
                        providerAvailable = true,
                        replyText = offlineReply
                    )
                } else {
                    ModeRoutingOutcome(
                        mode = AssistantEngineMode.MAYA_OFFLINE,
                        handledByProvider = mayaOfflineProvider.providerId,
                        providerAvailable = false,
                        replyText = "⚠️ **MAYA Offline (Local Qwen2.5-0.5B GGUF) Unavailable**\n\nThe local GGUF model file is not ready or verified on this device yet. Open **Settings → Offline AI Model** to download/verify `qwen2.5-0.5b-instruct-q4_0.gguf`, or switch to **MAYA Online**.",
                        errorCode = "MAYA_OFFLINE_MODEL_NOT_READY"
                    )
                }
            }

            AssistantEngineMode.MAYA_ONLINE -> {
                val specialistType = if (config.autoDetectSpecialist) {
                    TaskClassifier.classify(prompt)
                } else {
                    config.selectedSpecialist
                }
                val llmReq = LlmRequest(
                    prompt = prompt,
                    history = history,
                    character = character,
                    config = config.copy(onlineFeaturesEnabled = true),
                    specialistType = specialistType
                )
                val onlineReply = mayaOnlineProvider.generateOnline(llmReq)
                ModeRoutingOutcome(
                    mode = AssistantEngineMode.MAYA_ONLINE,
                    handledByProvider = mayaOnlineProvider.providerId,
                    providerAvailable = true,
                    replyText = onlineReply
                )
            }

            AssistantEngineMode.TESSA_TURBO_V1 -> {
                if (tessaAgent != null) {
                    val outcome = tessaAgent.handleUserRequest(
                        userRequest = prompt,
                        config = config,
                        conversationHistory = history
                    )
                    ModeRoutingOutcome(
                        mode = AssistantEngineMode.TESSA_TURBO_V1,
                        handledByProvider = tessaModelProvider.providerName,
                        providerAvailable = outcome.providerAvailable,
                        replyText = outcome.formattedResponse,
                        tessaTask = outcome.task,
                        errorCode = outcome.errorCode
                    )
                } else {
                    val modelReq = TessaModelRequest(
                        userPrompt = prompt,
                        conversationHistory = history,
                        tessaModelId = config.tessaModelId,
                        tessaApiBaseUrl = config.tessaApiBaseUrl,
                        tessaApiKey = config.resolvedTessaApiKey()
                    )
                    val modelResp = tessaModelProvider.generate(modelReq)
                    ModeRoutingOutcome(
                        mode = AssistantEngineMode.TESSA_TURBO_V1,
                        handledByProvider = tessaModelProvider.providerName,
                        providerAvailable = modelResp.available,
                        replyText = if (modelResp.available) {
                            modelResp.content
                        } else {
                            "⚠️ **TESSA Turbo V1 Provider Unavailable** (${modelResp.errorCode}): ${modelResp.errorMessage}"
                        },
                        errorCode = modelResp.errorCode
                    )
                }
            }
        }
    }
}
