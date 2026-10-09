package com.cocateail.maya.tessa.computer

import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BrowserPageState(
    val url: String,
    val title: String,
    val textContent: String,
    val clickableElements: List<String> = emptyList(),
    val inputFields: List<String> = emptyList(),
    val requiresAuthentication: Boolean = false,
    val authRequirementReason: String? = null,
    val screenshotBase64: String? = null
)

interface BrowserConnector {
    fun isAvailable(): Boolean
    suspend fun openUrl(taskId: String, url: String): Result<BrowserPageState>
    suspend fun inspectPage(taskId: String): Result<BrowserPageState>
    suspend fun click(taskId: String, selectorOrText: String): Result<BrowserPageState>
    suspend fun type(taskId: String, selector: String, text: String): Result<BrowserPageState>
    suspend fun search(taskId: String, query: String): Result<BrowserPageState>
    suspend fun closeSession(taskId: String): Result<Boolean>
}

class RealBrowserConnector(
    private val computerConnector: LocalComputerConnector? = null
) : BrowserConnector {

    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean =
        computerConnector?.isConnected() == true

    override suspend fun openUrl(taskId: String, url: String): Result<BrowserPageState> {
        return executeBrowserAction(taskId, "OPEN_URL", mapOf("url" to url))
    }

    override suspend fun inspectPage(taskId: String): Result<BrowserPageState> {
        return executeBrowserAction(taskId, "INSPECT_PAGE", emptyMap())
    }

    override suspend fun click(taskId: String, selectorOrText: String): Result<BrowserPageState> {
        return executeBrowserAction(taskId, "CLICK", mapOf("target" to selectorOrText))
    }

    override suspend fun type(taskId: String, selector: String, text: String): Result<BrowserPageState> {
        return executeBrowserAction(taskId, "TYPE", mapOf("selector" to selector, "text" to text))
    }

    override suspend fun search(taskId: String, query: String): Result<BrowserPageState> {
        val searchUrl = "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        return openUrl(taskId, searchUrl)
    }

    override suspend fun closeSession(taskId: String): Result<Boolean> {
        if (!isAvailable()) return Result.success(true)
        val resp = computerConnector?.executeRequest(
            ComputerAgentRequest(
                deviceId = computerConnector.getPairedComputer()?.computerId.orEmpty(),
                sessionToken = computerConnector.getPairedComputer()?.sessionToken.orEmpty(),
                taskId = taskId,
                action = "BROWSER_CLOSE"
            )
        )
        return Result.success(resp?.success == true)
    }

    private suspend fun executeBrowserAction(
        taskId: String,
        action: String,
        params: Map<String, String>
    ): Result<BrowserPageState> {
        val conn = computerConnector ?: return Result.failure(
            ComputerException("TOOL_UNAVAILABLE", "Browser connector requires paired computer connection.")
        )
        if (!conn.isConnected()) {
            return Result.failure(
                ComputerException("TOOL_UNAVAILABLE", "Paired computer is currently disconnected.")
            )
        }

        val request = ComputerAgentRequest(
            deviceId = conn.getPairedComputer()?.computerId.orEmpty(),
            sessionToken = conn.getPairedComputer()?.sessionToken.orEmpty(),
            taskId = taskId,
            action = action,
            target = params["url"] ?: params["target"] ?: "",
            permission = TessaPermissionType.USE_BROWSER.name,
            parameters = params
        )

        val response = conn.executeRequest(request)
        if (!response.success) {
            return Result.failure(ComputerException(response.errorCode ?: "BROWSER_ERROR", response.outputSummary))
        }

        val rawState = response.data["pageStateJson"]
        if (rawState != null) {
            val state = runCatching { json.decodeFromString<BrowserPageState>(rawState) }.getOrNull()
            if (state != null) return Result.success(state)
        }

        // Detect if page requested authentication
        val bodyText = response.outputSummary
        val requiresAuth = detectAuthPrompt(bodyText)

        val fallbackState = BrowserPageState(
            url = response.data["url"] ?: params["url"] ?: "about:blank",
            title = response.data["title"] ?: "Browser View",
            textContent = bodyText,
            requiresAuthentication = requiresAuth.first,
            authRequirementReason = requiresAuth.second,
            screenshotBase64 = response.data["screenshotBase64"]
        )

        return Result.success(fallbackState)
    }

    companion object {
        fun detectAuthPrompt(text: String): Pair<Boolean, String?> {
            val lower = text.lowercase()
            return when {
                lower.contains("enter otp") || lower.contains("enter one-time password") || lower.contains("verification code sent") ->
                    true to "OTP verification requested by webpage"
                lower.contains("solve captcha") || lower.contains("recaptcha") || lower.contains("verify you are human") || lower.contains("cloudflare turnstile") ->
                    true to "CAPTCHA challenge presented on webpage"
                lower.contains("two-factor authentication") || lower.contains("2fa") || lower.contains("approve in your authenticator") ->
                    true to "Two-factor authentication (2FA) prompt"
                lower.contains("enter your password") || lower.contains("sign in with your password") || lower.contains("login to continue") ->
                    true to "User login/password requested by webpage"
                else -> false to null
            }
        }
    }
}

class BrowserTool(
    private val browserConnector: BrowserConnector? = null
) : TessaTool {

    companion object {
        const val TOOL_ID = "BROWSER_TOOL"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Authorized Web Browser Agent"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.USE_BROWSER)

    override fun isAvailable(): Boolean = browserConnector?.isAvailable() == true

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        if (!isAvailable()) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "TOOL_UNAVAILABLE: Browser connector is not available. Connect a paired computer to enable web browsing.",
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = listOf(TessaPermissionType.USE_BROWSER)
        )

        if (!permDecision.allowed) {
            return TessaToolResult(
                toolId = toolId,
                status = if (permDecision.state == TessaPermissionState.BLOCKED_PROHIBITED_ACTION) {
                    TessaToolStatus.BLOCKED_SECURITY
                } else {
                    TessaToolStatus.PERMISSION_REQUIRED
                },
                outputSummary = permDecision.message,
                errorCode = permDecision.blockedReasonCode ?: "PERMISSION_REQUIRED",
                missingPermissions = permDecision.missingPermissions
            )
        }

        val action = request.action.trim().uppercase()
        val target = request.parameters["url"] ?: request.parameters["target"] ?: request.userInstruction

        val pageStateResult = when (action) {
            "SEARCH" -> browserConnector!!.search(request.taskId, target)
            "CLICK" -> browserConnector!!.click(request.taskId, target)
            "TYPE" -> {
                val selector = request.parameters["selector"] ?: "input"
                val text = request.parameters["text"].orEmpty()
                browserConnector!!.type(request.taskId, selector, text)
            }
            "INSPECT" -> browserConnector!!.inspectPage(request.taskId)
            else -> browserConnector!!.openUrl(request.taskId, target)
        }

        if (pageStateResult.isFailure) {
            val err = pageStateResult.exceptionOrNull()
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.FAILED,
                outputSummary = "Browser action failed: ${err?.message}",
                errorCode = "BROWSER_ACTION_FAILED"
            )
        }

        val pageState = pageStateResult.getOrThrow()

        // Authentication boundary check: pause if OTP/password/CAPTCHA/2FA is encountered
        if (pageState.requiresAuthentication) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.PERMISSION_REQUIRED,
                outputSummary = "WAITING_FOR_USER_AUTHENTICATION: ${pageState.authRequirementReason}. Please complete authentication directly on your device/browser.",
                structuredData = mapOf(
                    "url" to pageState.url,
                    "title" to pageState.title,
                    "authRequired" to "true",
                    "authReason" to (pageState.authRequirementReason ?: "Authentication Required")
                ),
                errorCode = "WAITING_FOR_USER_AUTHENTICATION"
            )
        }

        val preview = pageState.textContent.take(300)
        return TessaToolResult(
            toolId = toolId,
            status = TessaToolStatus.SUCCESS,
            outputSummary = "Browser on [${pageState.title}](${pageState.url}): $preview",
            structuredData = buildMap {
                put("url", pageState.url)
                put("title", pageState.title)
                put("textLength", pageState.textContent.length.toString())
                if (pageState.screenshotBase64 != null) {
                    put("hasScreenshot", "true")
                }
            }
        )
    }
}
