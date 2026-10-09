package com.cocateail.maya.tessa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.studio.BuilderAgent
import com.cocateail.maya.studio.BuilderProjectType
import com.cocateail.maya.ui.components.GeneratedProject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class TessaToolStatus {
    SUCCESS,
    TOOL_UNAVAILABLE,
    PERMISSION_REQUIRED,
    BLOCKED_SECURITY,
    MISSING_USER_INPUT,
    FAILED
}

@Serializable
data class TessaToolRequest(
    val taskId: String,
    val toolId: String,
    val action: String,
    val userInstruction: String,
    val parameters: Map<String, String> = emptyMap(),
    val pdfBytesBase64: String? = null
)

@Serializable
data class TessaToolResult(
    val toolId: String,
    val status: TessaToolStatus,
    val outputSummary: String,
    val structuredData: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val missingPermissions: List<TessaPermissionType> = emptyList(),
    val handoffProjectName: String? = null,
    val handoffFileCount: Int = 0
) {
    val isSuccess: Boolean
        get() = status == TessaToolStatus.SUCCESS
}

interface TessaTool {
    val toolId: String
    val displayName: String
    val requiredPermissionsForDefaultAction: List<TessaPermissionType>

    fun isAvailable(): Boolean
    suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult
}

// ============================================================================
// 1. MESSAGE & PDF INTELLIGENCE TOOL
// ============================================================================

@Serializable
data class MessagePdfAnalysisReport(
    val whatIsThis: String,
    val isActionRequired: Boolean,
    val requestedAction: String,
    val isPermissionRequired: Boolean,
    val requiredPermissions: List<TessaPermissionType>,
    val targetHandlerTool: String,
    val whatShouldHappenNext: List<String>,
    val extractedTextPreview: String,
    val relatedProjectName: String? = null
)

class MessagePdfIntelligenceTool : TessaTool {
    companion object {
        const val TOOL_ID = "MESSAGE_PDF_INTELLIGENCE"

        /**
         * Extracts readable text from real PDF bytes (`%PDF-` header validation + PDF text operators
         * `(...) Tj`, `[...] TJ`, and readable ASCII/UTF-8 text runs inside streams).
         * Returns null if the bytes are empty or not a valid PDF document.
         */
        fun extractTextFromPdfBytes(pdfBytes: ByteArray): String? {
            if (pdfBytes.size < 8) return null
            val header = String(pdfBytes, 0, minOf(pdfBytes.size, 16), Charsets.ISO_8859_1)
            if (!header.startsWith("%PDF-")) return null

            val rawIso = String(pdfBytes, Charsets.ISO_8859_1)
            val extractedSegments = mutableListOf<String>()

            // 1. Extract literal strings inside PDF text showing operators: (text) Tj or [(text)] TJ
            val parenthesizedRegex = Regex("\\(([^()\\\\]*(?:\\\\.[^()\\\\]*)*)\\)\\s*(?:Tj|'|\"|)?")
            for (match in parenthesizedRegex.findAll(rawIso)) {
                val rawToken = match.groupValues[1]
                    .replace("\\n", "\n")
                    .replace("\\r", " ")
                    .replace("\\t", " ")
                    .replace("\\(", "(")
                    .replace("\\)", ")")
                    .replace("\\\\", "\\")
                    .trim()
                if (rawToken.length >= 2 && rawToken.any { it.isLetterOrDigit() }) {
                    extractedSegments.add(rawToken)
                }
            }

            if (extractedSegments.isNotEmpty()) {
                return extractedSegments.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            }

            // 2. Fallback: extract printable text runs outside binary stream headers
            val printable = rawIso
                .lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                        !line.startsWith("%PDF") &&
                        !line.startsWith("<<") &&
                        !line.startsWith(">>") &&
                        !line.endsWith("obj") &&
                        line != "endobj" &&
                        line != "stream" &&
                        line != "endstream" &&
                        line != "xref" &&
                        line != "trailer" &&
                        line.count { ch -> ch.isLetterOrDigit() || ch == ' ' } > (line.length * 0.7)
                }
            return printable.joinToString("\n").trim().ifBlank { null }
        }
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Message & PDF Intelligence Reader"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.READ_EMAIL, TessaPermissionType.READ_ATTACHMENT, TessaPermissionType.READ_PDF)

    override fun isAvailable(): Boolean = true

    fun analyzeContent(
        userPrompt: String,
        messageText: String?,
        attachmentName: String?,
        pdfBytes: ByteArray?
    ): MessagePdfAnalysisReport {
        val pdfText = if (pdfBytes != null && pdfBytes.isNotEmpty()) {
            extractTextFromPdfBytes(pdfBytes).orEmpty()
        } else {
            ""
        }
        val combinedText = buildString {
            if (!messageText.isNullOrBlank()) appendLine(messageText.trim())
            if (pdfText.isNotBlank()) appendLine(pdfText.trim())
            if (isEmpty()) append(userPrompt.trim())
        }.trim()

        val lower = (userPrompt + "\n" + combinedText).lowercase()

        val isProjectOrBuildUpdate = lower.contains("project") ||
            lower.contains("build") ||
            lower.contains("android") ||
            lower.contains("app") ||
            lower.contains("apk") ||
            lower.contains("feature") ||
            lower.contains("bug") ||
            lower.contains("fix") ||
            lower.contains("update") ||
            lower.contains("code") ||
            lower.contains("github")

        val isShoppingRequest = lower.contains("buy") ||
            lower.contains("order") ||
            lower.contains("amazon") ||
            lower.contains("flipkart") ||
            lower.contains("price") ||
            lower.contains("product")

        val isReplyOrSendMessage = lower.contains("reply") ||
            lower.contains("send email") ||
            lower.contains("send message")

        val whatIsThis = when {
            pdfText.isNotBlank() && isProjectOrBuildUpdate ->
                "Authorized message with PDF specification attachment (${attachmentName ?: "document.pdf"}) containing project/development instructions."
            pdfText.isNotBlank() ->
                "Authorized PDF document (${attachmentName ?: "document.pdf"}) with extracted text (${pdfText.length} chars)."
            !messageText.isNullOrBlank() && isProjectOrBuildUpdate ->
                "Authorized email/message containing a software project update instruction."
            !messageText.isNullOrBlank() ->
                "Authorized email/message content for analysis."
            else ->
                "Direct user instruction for message/document intelligence."
        }

        val actionRequired = isProjectOrBuildUpdate || isShoppingRequest || isReplyOrSendMessage

        val requestedAction = when {
            isProjectOrBuildUpdate ->
                "Analyze requirements from message/PDF and hand off project update to MAYA Builder."
            isShoppingRequest ->
                "Check product details via Shopping Tool (order placement requires explicit permission)."
            isReplyOrSendMessage ->
                "Draft and send message reply (requires explicit SEND_MESSAGE permission)."
            else ->
                "Summarize message/PDF contents for the user (no external side effect required)."
        }

        val requiredPerms = buildList {
            if (!messageText.isNullOrBlank()) add(TessaPermissionType.READ_EMAIL)
            if (!attachmentName.isNullOrBlank()) add(TessaPermissionType.READ_ATTACHMENT)
            if (pdfBytes != null && pdfBytes.isNotEmpty()) add(TessaPermissionType.READ_PDF)
            if (isProjectOrBuildUpdate) add(TessaPermissionType.WRITE_PROJECT)
            if (lower.contains("push") && lower.contains("github")) add(TessaPermissionType.PUSH_GITHUB)
            if (isReplyOrSendMessage) add(TessaPermissionType.SEND_MESSAGE)
            if (isShoppingRequest && (lower.contains("order") || lower.contains("buy"))) {
                add(TessaPermissionType.PLACE_ORDER)
            }
        }.distinct()

        val targetTool = when {
            isProjectOrBuildUpdate -> MayaBuilderTaskHandoff.TOOL_ID
            isShoppingRequest -> ShoppingTool.TOOL_ID
            else -> TOOL_ID
        }

        val steps = buildList {
            add("READ_MESSAGE")
            if (!attachmentName.isNullOrBlank() || (pdfBytes != null && pdfBytes.isNotEmpty())) {
                add("READ_ATTACHMENT")
            }
            add("UNDERSTAND_TASK")
            add("CREATE_TASK")
            if (requiredPerms.any { !it.isReadOnly }) {
                add("REQUEST_PERMISSION")
            }
            if (isProjectOrBuildUpdate) {
                add("HANDOFF_TO_MAYA_BUILDER")
            }
            add("TRACK_RESULT")
            add("REPORT_RESULT")
        }

        val detectedProject = TessaPlanner.extractProjectName(combinedText)

        return MessagePdfAnalysisReport(
            whatIsThis = whatIsThis,
            isActionRequired = actionRequired,
            requestedAction = requestedAction,
            isPermissionRequired = requiredPerms.isNotEmpty(),
            requiredPermissions = requiredPerms,
            targetHandlerTool = targetTool,
            whatShouldHappenNext = steps,
            extractedTextPreview = combinedText.take(500),
            relatedProjectName = detectedProject
        )
    }

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        val messageText = request.parameters["messageText"]?.trim()
        val attachmentName = request.parameters["attachmentName"]?.trim()
        val pdfBytes: ByteArray? = request.pdfBytesBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()
        }

        // If user asked to read an external inbox without supplying message text or PDF bytes,
        // do NOT fake an inbox connection; return TOOL_UNAVAILABLE honestly.
        if (messageText.isNullOrBlank() && (pdfBytes == null || pdfBytes.isEmpty())) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "Live external email inbox connector is not configured. Provide the email/message text or attach a PDF file for Tessa to analyze.",
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        // If PDF bytes were provided, validate that they are a real PDF
        if (pdfBytes != null && pdfBytes.isNotEmpty()) {
            val extracted = extractTextFromPdfBytes(pdfBytes)
            if (extracted == null) {
                return TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Invalid or corrupted PDF attachment: failed %PDF- header or stream extraction.",
                    errorCode = "INVALID_PDF_BYTES"
                )
            }
        }

        val neededReadPerms = buildList {
            if (!messageText.isNullOrBlank()) add(TessaPermissionType.READ_EMAIL)
            if (!attachmentName.isNullOrBlank()) add(TessaPermissionType.READ_ATTACHMENT)
            if (pdfBytes != null && pdfBytes.isNotEmpty()) add(TessaPermissionType.READ_PDF)
        }

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = neededReadPerms
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

        val report = analyzeContent(
            userPrompt = request.userInstruction,
            messageText = messageText,
            attachmentName = attachmentName,
            pdfBytes = pdfBytes
        )

        permissionManager.recordTraceableSideEffect(
            userRequest = request.userInstruction,
            taskId = request.taskId,
            permissionState = permDecision.state,
            toolName = toolId,
            resultSummary = "Analyzed message/PDF (${report.whatIsThis}); targetTool=${report.targetHandlerTool}",
            explicitSecrets = listOf(config.tessaApiKey, config.githubToken, config.customApiKey)
        )

        val summary = buildString {
            appendLine("📄 **Tessa Message/PDF Intelligence Report**")
            appendLine("1. **What is this:** ${report.whatIsThis}")
            appendLine("2. **Action required:** ${if (report.isActionRequired) "Yes" else "No"}")
            appendLine("3. **Requested action:** ${report.requestedAction}")
            appendLine("4. **Permissions required:** ${if (report.requiredPermissions.isEmpty()) "None" else report.requiredPermissions.joinToString { it.name }}")
            appendLine("5. **Target tool:** `${report.targetHandlerTool}`")
            append("6. **Execution pipeline:** ${report.whatShouldHappenNext.joinToString(" → ")}")
        }

        return TessaToolResult(
            toolId = toolId,
            status = TessaToolStatus.SUCCESS,
            outputSummary = summary,
            structuredData = mapOf(
                "whatIsThis" to report.whatIsThis,
                "isActionRequired" to report.isActionRequired.toString(),
                "requestedAction" to report.requestedAction,
                "isPermissionRequired" to report.isPermissionRequired.toString(),
                "requiredPermissions" to report.requiredPermissions.joinToString(",") { it.name },
                "targetHandlerTool" to report.targetHandlerTool,
                "whatShouldHappenNext" to report.whatShouldHappenNext.joinToString("->"),
                "extractedTextPreview" to report.extractedTextPreview,
                "relatedProjectName" to (report.relatedProjectName ?: "")
            ),
            handoffProjectName = report.relatedProjectName
        )
    }
}

// ============================================================================
// 2. MAYA BUILDER & GITHUB HANDOFF TOOL
// ============================================================================

class MayaBuilderTaskHandoff(
    private val context: Context? = null,
    private val builderDelegate: (suspend (String, BuilderProjectType) -> GeneratedProject)? = null
) : TessaTool {
    companion object {
        const val TOOL_ID = "MAYA_BUILDER_HANDOFF"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "MAYA Builder & GitHub Orchestrator Handoff"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.WRITE_PROJECT)

    override fun isAvailable(): Boolean = context != null || builderDelegate != null

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        if (!isAvailable()) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "MAYA Builder handoff is unavailable because Android Context / BuilderAgent is not attached.",
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        val wantsGitHubPush = request.parameters["pushToGitHub"]?.equals("true", ignoreCase = true) == true ||
            (request.userInstruction.lowercase().contains("push") && request.userInstruction.lowercase().contains("github"))

        val neededPerms = buildList {
            add(TessaPermissionType.WRITE_PROJECT)
            if (wantsGitHubPush) add(TessaPermissionType.PUSH_GITHUB)
        }

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = neededPerms
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

        val effectivePrompt = buildString {
            append(request.userInstruction)
            request.parameters["extractedContext"]?.takeIf { it.isNotBlank() }?.let { ctx ->
                append("\n\nSpecification Context:\n")
                append(ctx)
            }
        }

        val projectType = BuilderAgent.classifyProjectType(effectivePrompt)

        return try {
            val generatedProject: GeneratedProject = if (builderDelegate != null) {
                builderDelegate.invoke(effectivePrompt, projectType)
            } else {
                val realBuilder = BuilderAgent(requireNotNull(context))
                realBuilder.executeTask(effectivePrompt, projectType) { _, _, _ -> }
            }

            if (generatedProject.files.isEmpty()) {
                return TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "MAYA Builder returned 0 generated files for task ${request.taskId}.",
                    errorCode = "BUILDER_EMPTY_OUTPUT"
                )
            }

            permissionManager.recordTraceableSideEffect(
                userRequest = request.userInstruction,
                taskId = request.taskId,
                permissionState = permDecision.state,
                toolName = toolId,
                resultSummary = "Handed off task to MAYA Builder; generated project '${generatedProject.title}' (${generatedProject.files.size} files, type=${projectType.name}).",
                explicitSecrets = listOf(config.tessaApiKey, config.githubToken, config.customApiKey)
            )

            TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.SUCCESS,
                outputSummary = "✅ **Tessa → MAYA Builder Handoff Completed**\n" +
                    "• Project: `${generatedProject.title}`\n" +
                    "• Project Type: `${projectType.name}`\n" +
                    "• Generated Files: **${generatedProject.files.size}** verified workspace files.",
                structuredData = mapOf(
                    "projectName" to generatedProject.title,
                    "projectType" to projectType.name,
                    "fileCount" to generatedProject.files.size.toString(),
                    "files" to generatedProject.files.joinToString(",") { file -> file.path }
                ),
                handoffProjectName = generatedProject.title,
                handoffFileCount = generatedProject.files.size
            )
        } catch (e: Exception) {
            TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.FAILED,
                outputSummary = "MAYA Builder handoff failed: ${e.localizedMessage ?: "build error"}",
                errorCode = "BUILDER_HANDOFF_FAILED"
            )
        }
    }
}

// ============================================================================
// 3. LOCAL COMPUTER AGENT INTERFACE (V1 FOUNDATION)
// ============================================================================

enum class LocalComputerCapability {
    FILES,
    TERMINAL,
    APPLICATIONS,
    BROWSER,
    PROJECT_BUILDS,
    RESULT_RETRIEVAL
}

interface LocalComputerConnector {
    fun isConnected(): Boolean
    fun supportedCapabilities(): Set<LocalComputerCapability>
    suspend fun executeDesktopCommand(capability: LocalComputerCapability, payload: Map<String, String>): Pair<Boolean, String>
}

/**
 * Safe V1 foundation for a future Local Computer Agent.
 * Does NOT pretend the Android phone itself is a desktop computer.
 * When no real desktop connector is attached, returns `TOOL_UNAVAILABLE`.
 */
class LocalComputerTool(
    private val connector: LocalComputerConnector? = null,
    private val modernConnector: com.cocateail.maya.tessa.computer.LocalComputerConnector? = null
) : TessaTool {
    companion object {
        const val TOOL_ID = "LOCAL_COMPUTER_TOOL"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Local Computer Agent (Desktop Connector)"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.WRITE_PROJECT)

    override fun isAvailable(): Boolean =
        connector?.isConnected() == true || modernConnector?.isConnected() == true

    fun detectCapabilities(): Set<LocalComputerCapability> {
        if (connector?.isConnected() == true) {
            return connector.supportedCapabilities()
        }
        if (modernConnector?.isConnected() == true) {
            val modernCaps = modernConnector.getPairedComputer()?.capabilities.orEmpty()
            return buildSet {
                if (modernCaps.contains(com.cocateail.maya.tessa.computer.ComputerCapability.FILE_READ) ||
                    modernCaps.contains(com.cocateail.maya.tessa.computer.ComputerCapability.FILE_WRITE)
                ) {
                    add(LocalComputerCapability.FILES)
                }
                if (modernCaps.contains(com.cocateail.maya.tessa.computer.ComputerCapability.TERMINAL)) {
                    add(LocalComputerCapability.TERMINAL)
                }
                if (modernCaps.contains(com.cocateail.maya.tessa.computer.ComputerCapability.APP_LAUNCH)) {
                    add(LocalComputerCapability.APPLICATIONS)
                }
                if (modernCaps.contains(com.cocateail.maya.tessa.computer.ComputerCapability.BROWSER)) {
                    add(LocalComputerCapability.BROWSER)
                }
                if (modernCaps.contains(com.cocateail.maya.tessa.computer.ComputerCapability.BUILD)) {
                    add(LocalComputerCapability.PROJECT_BUILDS)
                }
                add(LocalComputerCapability.RESULT_RETRIEVAL)
            }
        }
        return emptySet()
    }

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        if (!isAvailable()) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "TOOL_UNAVAILABLE: No real Local Computer Agent desktop connector is connected. Tessa on Android will not pretend the phone is a desktop computer.",
                structuredData = mapOf(
                    "connectorConnected" to "false",
                    "plannedCapabilities" to LocalComputerCapability.entries.joinToString(",") { it.name }
                ),
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = requiredPermissionsForDefaultAction
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

        if (connector != null) {
            val capability = runCatching {
                LocalComputerCapability.valueOf(request.action.uppercase())
            }.getOrDefault(LocalComputerCapability.FILES)

            val (ok, msg) = connector.executeDesktopCommand(capability, request.parameters)
            return TessaToolResult(
                toolId = toolId,
                status = if (ok) TessaToolStatus.SUCCESS else TessaToolStatus.FAILED,
                outputSummary = msg,
                errorCode = if (ok) null else "DESKTOP_EXECUTION_FAILED"
            )
        } else if (modernConnector != null) {
            val agentReq = com.cocateail.maya.tessa.computer.ComputerAgentRequest(
                deviceId = modernConnector.getPairedComputer()?.computerId.orEmpty(),
                sessionToken = modernConnector.getPairedComputer()?.sessionToken.orEmpty(),
                taskId = request.taskId,
                action = request.action,
                parameters = request.parameters
            )
            val resp = modernConnector.executeRequest(agentReq)
            return TessaToolResult(
                toolId = toolId,
                status = if (resp.success) TessaToolStatus.SUCCESS else TessaToolStatus.FAILED,
                outputSummary = resp.outputSummary,
                structuredData = resp.data,
                errorCode = resp.errorCode
            )
        }

        return TessaToolResult(
            toolId = toolId,
            status = TessaToolStatus.TOOL_UNAVAILABLE,
            outputSummary = "TOOL_UNAVAILABLE: No connector instance available.",
            errorCode = "TOOL_UNAVAILABLE"
        )
    }
}

// ============================================================================
// 4. SHOPPING AGENT INTERFACE (V1 FOUNDATION)
// ============================================================================

enum class ShoppingMarketplaceProvider(val displayName: String) {
    AMAZON("Amazon"),
    FLIPKART("Flipkart"),
    OTHER_MARKETPLACE("Supported Marketplace")
}

@Serializable
data class ShoppingProductCandidate(
    val provider: ShoppingMarketplaceProvider,
    val productId: String,
    val title: String,
    val priceFormatted: String,
    val inStock: Boolean,
    val deliveryEstimate: String
)

interface ShoppingMarketplaceConnector {
    fun isProviderConnected(provider: ShoppingMarketplaceProvider): Boolean
    suspend fun searchProducts(query: String, provider: ShoppingMarketplaceProvider?): List<ShoppingProductCandidate>
    suspend fun compareProducts(productIds: List<String>): String
    suspend fun checkAvailability(productId: String): Boolean
    suspend fun checkDelivery(productId: String, pinCode: String): String
    suspend fun prepareOrderDraft(productId: String, addressSummary: String): String
}

/**
 * V1 Shopping Foundation:
 * Supports `searchProducts`, `compareProducts`, `checkAvailability`, `checkDelivery`, and `prepareOrder`.
 * - NEVER automatically places real orders in V1.
 * - Order preparation/placement requires explicit `PLACE_ORDER` permission.
 * - Payment (`USE_PAYMENT`) is strictly blocked from Tessa execution and always remains user-controlled.
 * - If no live marketplace connector is configured, returns `TOOL_UNAVAILABLE`.
 */
class ShoppingTool(
    private val connector: ShoppingMarketplaceConnector? = null
) : TessaTool {
    companion object {
        const val TOOL_ID = "SHOPPING_TOOL"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Shopping Agent (Amazon / Flipkart Foundation)"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> = emptyList()

    override fun isAvailable(): Boolean {
        val c = connector ?: return false
        return ShoppingMarketplaceProvider.entries.any { c.isProviderConnected(it) }
    }

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        val actionUpper = request.action.trim().uppercase()
        val lowerInstruction = request.userInstruction.lowercase()

        // 1. Payment is ALWAYS user-controlled — block any payment attempt immediately
        if (actionUpper.contains("PAY") ||
            lowerInstruction.contains("pay now") ||
            lowerInstruction.contains("upi") ||
            lowerInstruction.contains("cvv") ||
            lowerInstruction.contains("complete payment")
        ) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.BLOCKED_SECURITY,
                outputSummary = "BLOCKED: Payment execution (USE_PAYMENT) must ALWAYS remain strictly user-controlled. Tessa will never enter payment credentials, read OTPs, or approve payments.",
                errorCode = "PAYMENT_ALWAYS_USER_CONTROLLED",
                missingPermissions = listOf(TessaPermissionType.USE_PAYMENT)
            )
        }

        // 2. Order placement / preparation requires explicit PLACE_ORDER permission
        val isOrderAction = actionUpper == "PREPARE_ORDER" ||
            actionUpper == "PLACE_ORDER" ||
            lowerInstruction.contains("place order") ||
            lowerInstruction.contains("buy ") ||
            lowerInstruction.contains("order ")

        if (isOrderAction) {
            val permDecision = permissionManager.evaluatePermissions(
                taskId = request.taskId,
                userRequest = request.userInstruction,
                requiredPermissions = listOf(TessaPermissionType.PLACE_ORDER)
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
        }

        // 3. Check real marketplace connector availability
        if (!isAvailable()) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "TOOL_UNAVAILABLE: Shopping marketplace connector (Amazon / Flipkart) is not connected in V1. Tessa does not fabricate product listings or place fake orders.",
                structuredData = mapOf(
                    "requestedOperation" to actionUpper.ifBlank { "SEARCH_PRODUCTS" },
                    "supportedInterfaces" to "searchProducts,compareProducts,checkAvailability,checkDelivery,prepareOrder"
                ),
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        val c = connector!!
        return when (actionUpper) {
            "SEARCH_PRODUCTS" -> {
                val query = request.parameters["query"] ?: request.userInstruction
                val items = c.searchProducts(query, null)
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = "Found ${items.size} marketplace product(s) for '$query'."
                )
            }
            "COMPARE_PRODUCTS" -> {
                val ids = request.parameters["productIds"]?.split(",")?.map { it.trim() }.orEmpty()
                val comparison = c.compareProducts(ids)
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = comparison
                )
            }
            "CHECK_AVAILABILITY" -> {
                val pid = request.parameters["productId"].orEmpty()
                val available = c.checkAvailability(pid)
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = "Product $pid availability: $available"
                )
            }
            "CHECK_DELIVERY" -> {
                val pid = request.parameters["productId"].orEmpty()
                val pin = request.parameters["pinCode"].orEmpty()
                val delivery = c.checkDelivery(pid, pin)
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = delivery
                )
            }
            "PREPARE_ORDER" -> {
                val pid = request.parameters["productId"].orEmpty()
                val addr = request.parameters["addressSummary"].orEmpty()
                val draft = c.prepareOrderDraft(pid, addr)
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = "$draft (Note: Final checkout and payment must be completed directly by the user.)"
                )
            }
            else -> TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "TOOL_UNAVAILABLE: Automatic order execution is disabled in V1.",
                errorCode = "TOOL_UNAVAILABLE"
            )
        }
    }
}

// ============================================================================
// 5. LOCATION & ADDRESS TOOL (PERMISSION-CONTROLLED FOUNDATION)
// ============================================================================

@Serializable
data class UserDeliveryContactProfile(
    val fullName: String = "",
    val houseNumber: String = "",
    val streetOrArea: String = "",
    val city: String = "",
    val state: String = "",
    val pinCode: String = "",
    val phoneNumber: String = ""
)

class LocationAddressTool(
    private val context: Context? = null
) : TessaTool {
    companion object {
        const val TOOL_ID = "LOCATION_TOOL"
        private val PIN_CODE_REGEX = Regex("^[1-9][0-9]{5}$")
        private val PHONE_REGEX = Regex("^\\+?[0-9]{10,14}$")
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Permission-Controlled Location & Address Tool"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.USE_LOCATION)

    private val prefs = context?.getSharedPreferences("maya_tessa_secure_address_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private var inMemorySavedProfile: UserDeliveryContactProfile? = null

    override fun isAvailable(): Boolean = true

    /**
     * Validates address & contact details without EVER guessing a house number or inventing an address.
     * If `houseNumber`, `streetOrArea`, `pinCode`, or `phoneNumber` is missing, returns the exact missing fields
     * so Tessa asks the user directly.
     */
    fun validateAddressNeverGuess(
        houseNumber: String?,
        streetOrArea: String?,
        pinCode: String?,
        phoneNumber: String?
    ): Pair<Boolean, List<String>> {
        val missingFields = mutableListOf<String>()
        if (houseNumber.isNullOrBlank()) {
            missingFields.add("houseNumber (Tessa never guesses a house/flat number — please provide it)")
        }
        if (streetOrArea.isNullOrBlank()) {
            missingFields.add("streetOrArea (Tessa never invents a street/locality — please provide it)")
        }
        val cleanPin = pinCode?.trim().orEmpty()
        if (cleanPin.isBlank() || !PIN_CODE_REGEX.matches(cleanPin)) {
            missingFields.add("pinCode (valid 6-digit PIN code required)")
        }
        val cleanPhone = phoneNumber?.trim()?.replace(" ", "")?.replace("-", "").orEmpty()
        if (cleanPhone.isBlank() || !PHONE_REGEX.matches(cleanPhone)) {
            missingFields.add("phoneNumber (valid user phone number required)")
        }
        return missingFields.isEmpty() to missingFields
    }

    fun getSavedProfile(
        taskId: String,
        permissionManager: TessaPermissionManager
    ): Pair<UserDeliveryContactProfile?, String> {
        val decision = permissionManager.evaluatePermissions(
            taskId = taskId,
            userRequest = "Read saved address profile",
            requiredPermissions = listOf(TessaPermissionType.USE_LOCATION)
        )
        if (!decision.allowed) {
            return null to decision.message
        }
        val raw = prefs?.getString("saved_delivery_profile", null)
        if (raw != null) {
            val decoded = runCatching { json.decodeFromString<UserDeliveryContactProfile>(raw) }.getOrNull()
            if (decoded != null) return decoded to "Loaded permitted delivery profile."
        }
        return inMemorySavedProfile to (if (inMemorySavedProfile != null) "Loaded permitted delivery profile." else "No saved delivery profile.")
    }

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        val actionUpper = request.action.trim().uppercase().ifBlank { "GET_LOCATION" }

        when (actionUpper) {
            "VALIDATE_OR_SAVE_ADDRESS" -> {
                val houseNumber = request.parameters["houseNumber"]?.trim()
                val street = request.parameters["streetOrArea"]?.trim()
                val city = request.parameters["city"]?.trim().orEmpty()
                val state = request.parameters["state"]?.trim().orEmpty()
                val pinCode = request.parameters["pinCode"]?.trim()
                val phoneNumber = request.parameters["phoneNumber"]?.trim()

                // Never guess house number or phone number: check completeness first
                val (valid, missing) = validateAddressNeverGuess(houseNumber, street, pinCode, phoneNumber)
                if (!valid) {
                    return TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.MISSING_USER_INPUT,
                        outputSummary = "Cannot save or confirm delivery address. Missing required user fields: ${missing.joinToString("; ")}",
                        structuredData = mapOf("missingFields" to missing.joinToString("|")),
                        errorCode = "MISSING_USER_ADDRESS_OR_PHONE"
                    )
                }

                // Require explicit SAVE_ADDRESS and USE_PHONE_NUMBER permissions
                val permDecision = permissionManager.evaluatePermissions(
                    taskId = request.taskId,
                    userRequest = request.userInstruction,
                    requiredPermissions = listOf(
                        TessaPermissionType.SAVE_ADDRESS,
                        TessaPermissionType.USE_PHONE_NUMBER
                    )
                )
                if (!permDecision.allowed) {
                    return TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.PERMISSION_REQUIRED,
                        outputSummary = permDecision.message,
                        errorCode = "PERMISSION_REQUIRED",
                        missingPermissions = permDecision.missingPermissions
                    )
                }

                val profile = UserDeliveryContactProfile(
                    fullName = request.parameters["fullName"]?.trim().orEmpty(),
                    houseNumber = houseNumber!!,
                    streetOrArea = street!!,
                    city = city,
                    state = state,
                    pinCode = pinCode!!,
                    phoneNumber = phoneNumber!!
                )
                inMemorySavedProfile = profile
                prefs?.edit()?.putString("saved_delivery_profile", json.encodeToString(profile))?.apply()

                permissionManager.recordTraceableSideEffect(
                    userRequest = request.userInstruction,
                    taskId = request.taskId,
                    permissionState = permDecision.state,
                    toolName = toolId,
                    resultSummary = "Saved user-confirmed delivery address for PIN ${profile.pinCode}."
                )

                return TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = "Confirmed and saved user delivery address (PIN: ${profile.pinCode}) with explicit SAVE_ADDRESS and USE_PHONE_NUMBER permissions.",
                    structuredData = mapOf("pinCode" to profile.pinCode)
                )
            }

            else -> {
                // Device location or PIN-code lookup requires explicit USE_LOCATION permission
                val permDecision = permissionManager.evaluatePermissions(
                    taskId = request.taskId,
                    userRequest = request.userInstruction,
                    requiredPermissions = listOf(TessaPermissionType.USE_LOCATION)
                )
                if (!permDecision.allowed) {
                    return TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.PERMISSION_REQUIRED,
                        outputSummary = permDecision.message,
                        errorCode = "PERMISSION_REQUIRED",
                        missingPermissions = permDecision.missingPermissions
                    )
                }

                val ctx = context
                if (ctx == null) {
                    return TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.TOOL_UNAVAILABLE,
                        outputSummary = "TOOL_UNAVAILABLE: Android Context not attached for device location lookup.",
                        errorCode = "TOOL_UNAVAILABLE"
                    )
                }

                val hasFine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFine && !hasCoarse) {
                    return TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.PERMISSION_REQUIRED,
                        outputSummary = "Android OS runtime location permission (ACCESS_COARSE_LOCATION / ACCESS_FINE_LOCATION) is not granted.",
                        errorCode = "ANDROID_LOCATION_PERMISSION_NOT_GRANTED",
                        missingPermissions = listOf(TessaPermissionType.USE_LOCATION)
                    )
                }

                val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val lastLoc = try {
                    locMgr?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: locMgr?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } catch (_: SecurityException) {
                    null
                }

                return if (lastLoc != null) {
                    TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.SUCCESS,
                        outputSummary = "Retrieved permitted device coordinates (${lastLoc.latitude}, ${lastLoc.longitude}). Note: House number and phone number must still be confirmed by the user.",
                        structuredData = mapOf(
                            "latitude" to lastLoc.latitude.toString(),
                            "longitude" to lastLoc.longitude.toString()
                        )
                    )
                } else {
                    TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.TOOL_UNAVAILABLE,
                        outputSummary = "TOOL_UNAVAILABLE: No cached GPS/Network location fix available on device.",
                        errorCode = "TOOL_UNAVAILABLE"
                    )
                }
            }
        }
    }
}

// ============================================================================
// TESSA TOOL ROUTER
// ============================================================================

class TessaToolRouter(
    tools: List<TessaTool>
) {
    private val toolMap: MutableMap<String, TessaTool> =
        tools.associateBy { it.toolId.uppercase() }.toMutableMap()

    fun registerTool(tool: TessaTool) {
        toolMap[tool.toolId.uppercase()] = tool
    }

    fun getTool(toolId: String): TessaTool? = toolMap[toolId.trim().uppercase()]

    fun listRegisteredTools(): List<TessaTool> = toolMap.values.toList()

    suspend fun routeAndExecute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        // 1. Check security prohibitions before invoking any tool
        val (prohibited, reason) = permissionManager.detectProhibitedAction(request.userInstruction)
        if (prohibited) {
            return TessaToolResult(
                toolId = request.toolId,
                status = TessaToolStatus.BLOCKED_SECURITY,
                outputSummary = reason ?: "Action blocked by Tessa security policy.",
                errorCode = reason?.substringBefore(":")?.trim() ?: "PROHIBITED_ACTION"
            )
        }

        // 2. Lookup registered tool; if unknown/missing, return TOOL_UNAVAILABLE
        val tool = getTool(request.toolId)
            ?: return TessaToolResult(
                toolId = request.toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "TOOL_UNAVAILABLE: Tool '${request.toolId}' is not registered or not available in TESSA Turbo V1.",
                errorCode = "TOOL_UNAVAILABLE"
            )

        // 3. Execute tool with permission enforcement
        return tool.execute(request, permissionManager, config)
    }

    companion object {
        fun createDefault(
            context: Context? = null,
            builderDelegate: (suspend (String, BuilderProjectType) -> GeneratedProject)? = null,
            localComputerConnector: LocalComputerConnector? = null,
            shoppingConnector: ShoppingMarketplaceConnector? = null,
            gmailConnector: com.cocateail.maya.tessa.gmail.GmailConnector? = null,
            modernComputerConnector: com.cocateail.maya.tessa.computer.LocalComputerConnector? = null,
            browserConnector: com.cocateail.maya.tessa.computer.BrowserConnector? = null,
            unrealConnector: com.cocateail.maya.tessa.computer.UnrealEngineConnector? = null
        ): TessaToolRouter {
            val builder = MayaBuilderTaskHandoff(context = context, builderDelegate = builderDelegate)
            val modernComp = modernComputerConnector ?: com.cocateail.maya.tessa.computer.RealLocalComputerConnector(context)
            val browserConn = browserConnector ?: com.cocateail.maya.tessa.computer.RealBrowserConnector(modernComp)
            val unrealConn = unrealConnector ?: com.cocateail.maya.tessa.computer.RealUnrealEngineConnector(modernComp)

            return TessaToolRouter(
                listOf(
                    MessagePdfIntelligenceTool(),
                    builder,
                    com.cocateail.maya.tessa.gmail.GmailTool(context = context, gmailConnector = gmailConnector, builderHandoff = builder),
                    LocalComputerTool(connector = localComputerConnector, modernConnector = modernComp),
                    com.cocateail.maya.tessa.computer.FileTool(connector = modernComp),
                    com.cocateail.maya.tessa.computer.TerminalTool(connector = modernComp),
                    com.cocateail.maya.tessa.computer.BrowserTool(browserConnector = browserConn),
                    com.cocateail.maya.tessa.computer.UnrealEngineTool(connector = unrealConn),
                    com.cocateail.maya.tessa.computer.EducationResearchTool(browserConnector = browserConn),
                    com.cocateail.maya.tessa.computer.ApplicationAssistantTool(browserConnector = browserConn),
                    ShoppingTool(connector = shoppingConnector),
                    LocationAddressTool(context = context)
                )
            )
        }
    }
}
