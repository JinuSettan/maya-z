package com.cocateail.maya.tessa

import com.cocateail.maya.data.db.TessaTaskEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

enum class AssistantEngineMode(
    val modeId: String,
    val displayName: String,
    val badgeLabel: String,
    val isLocked: Boolean = false
) {
    MAYA_OFFLINE(
        modeId = "MAYA_OFFLINE",
        displayName = "MAYA Offline",
        badgeLabel = "Local Qwen2.5-0.5B GGUF",
        isLocked = false
    ),
    MAYA_ONLINE(
        modeId = "MAYA_ONLINE",
        displayName = "MAYA Online",
        badgeLabel = "Gemini Online Chat",
        isLocked = false
    ),
    TESSA_TURBO_V1(
        modeId = "TESSA_TURBO_V1",
        displayName = "TESSA Turbo V1",
        badgeLabel = "Permission-Controlled Task Agent",
        isLocked = false
    ),
    SIMBA_TURBO_V2(
        modeId = "SIMBA_TURBO_V2",
        displayName = "SIMBA Turbo V2 🔒",
        badgeLabel = "Locked — Future Major Update",
        isLocked = true
    );

    companion object {
        fun fromConfigString(raw: String?): AssistantEngineMode {
            if (raw.isNullOrBlank()) return MAYA_ONLINE
            val clean = raw.trim().uppercase()
            return entries.firstOrNull {
                it.name.equals(clean, ignoreCase = true) ||
                    it.modeId.equals(clean, ignoreCase = true) ||
                    it.displayName.equals(clean, ignoreCase = true)
            } ?: MAYA_ONLINE
        }
    }
}

enum class TessaTaskState {
    CHAT,
    INFORMATION,
    TASK,
    ACTION,
    QUEUED,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_USER_AUTHENTICATION,
    WAITING_FOR_USER_DECISION,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    fun isTerminal(): Boolean =
        this == COMPLETED || this == FAILED || this == CANCELLED
}

enum class TessaUiStatus(val displayLabel: String) {
    READY("Ready"),
    THINKING("Thinking"),
    PLANNING("Planning"),
    WAITING_FOR_PERMISSION("Waiting for permission"),
    WAITING_FOR_AUTHENTICATION("Waiting for user authentication"),
    WAITING_FOR_DECISION("Waiting for user decision"),
    WORKING("Working"),
    WAITING("Waiting"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    LOCKED("Locked")
}

/**
 * Explicit runtime status model for TESSA Turbo V1:
 * - TESSA_READY: Model ID, API base URL, and API key configured, and provider initialized/reachable.
 * - TESSA_CONFIGURATION_REQUIRED: Missing model ID, API base URL, or API key in DeveloperConfig.
 * - TESSA_PROVIDER_UNAVAILABLE: Configured provider failed network handshake or endpoint returned 404/503.
 * - TESSA_AUTH_REQUIRED: Provider returned 401/403 (invalid or unauthorized API key).
 * - TESSA_ERROR: Unexpected runtime or parsing failure.
 */
enum class TessaRuntimeStatus(val displayLabel: String, val badgeColorHex: Long) {
    TESSA_READY("TESSA Ready", 0xFF38EF7D),
    TESSA_CONFIGURATION_REQUIRED("Configuration Required", 0xFFFF9800),
    TESSA_PROVIDER_UNAVAILABLE("Provider Unavailable", 0xFFE53935),
    TESSA_AUTH_REQUIRED("Authentication Required", 0xFFE53935),
    TESSA_ERROR("Runtime Error", 0xFFD32F2F)
}

@Serializable
data class TessaDiagnosticsInfo(
    val providerClass: String,
    val modelId: String = "",
    val baseUrl: String = "",
    val endpointPath: String = "",
    val isModelIdConfigured: Boolean,
    val isEndpointConfigured: Boolean,
    val isApiKeyConfigured: Boolean,
    val isProviderReachable: Boolean,
    val isRuntimeReady: Boolean,
    val runtimeStatus: TessaRuntimeStatus,
    val statusMessage: String,
    val connectionResult: String = "NOT_TESTED",
    val httpStatusCode: Int? = null,
    val errorCategory: String = "NONE",
    val sanitizedErrorBody: String = "",
    val retryAttempts: Int = 0,
    val latencyMs: Long? = null,
    val lastTestedTimestamp: Long = 0L
)

enum class TessaPermissionType(
    val isReadOnly: Boolean,
    val allowsReusableRule: Boolean,
    val alwaysRequiresExplicitPerActionApproval: Boolean,
    val description: String
) {
    READ_EMAIL(
        isReadOnly = true,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Read authorized email message content"
    ),
    READ_ATTACHMENT(
        isReadOnly = true,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Read authorized message attachment metadata and content"
    ),
    READ_PDF(
        isReadOnly = true,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Extract and read text from authorized PDF attachment"
    ),
    WRITE_PROJECT(
        isReadOnly = false,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Create or modify MAYA Builder project files"
    ),
    PUSH_GITHUB(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Push project files or trigger CI/CD on customer GitHub repository"
    ),
    SEND_MESSAGE(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Send an external message or email reply"
    ),
    DRAFT_EMAIL(
        isReadOnly = false,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Prepare and create an email draft in Gmail"
    ),
    SEND_EMAIL(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Send an email via Gmail (requires explicit user confirmation of recipient and content)"
    ),
    PLACE_ORDER(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Place or submit a shopping order"
    ),
    USE_LOCATION(
        isReadOnly = true,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Access device location or PIN-code lookup"
    ),
    SAVE_ADDRESS(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Save delivery address in secure storage"
    ),
    USE_PHONE_NUMBER(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Use or store user phone number for delivery/contact"
    ),
    USE_PAYMENT(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Payment execution (ALWAYS strictly user-controlled)"
    ),
    READ_LOCAL_FILES(
        isReadOnly = true,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Read files on paired local computer"
    ),
    WRITE_LOCAL_FILES(
        isReadOnly = false,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Create or modify files on paired local computer"
    ),
    DELETE_LOCAL_FILES(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Delete files or directories on paired local computer"
    ),
    RUN_TERMINAL(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Execute allowlisted command in safe terminal on paired computer"
    ),
    CONTROL_PROCESS(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Inspect or terminate processes on paired computer"
    ),
    OPEN_APPLICATION(
        isReadOnly = false,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Launch authorized applications on paired computer"
    ),
    USE_BROWSER(
        isReadOnly = false,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Perform browser navigation and DOM interaction"
    ),
    DOWNLOAD_FILE(
        isReadOnly = false,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Download file via browser or computer connector"
    ),
    UPLOAD_FILE(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Upload local file to external service or web portal"
    ),
    USE_UNREAL(
        isReadOnly = false,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Access Unreal Engine installation and project structure"
    ),
    BUILD_PROJECT(
        isReadOnly = false,
        allowsReusableRule = true,
        alwaysRequiresExplicitPerActionApproval = false,
        description = "Compile and build project (Unreal, Gradle, Cargo, CMake, etc.)"
    ),
    SUBMIT_APPLICATION(
        isReadOnly = false,
        allowsReusableRule = false,
        alwaysRequiresExplicitPerActionApproval = true,
        description = "Submit formal admission, education, or government application"
    )
}

enum class TessaPermissionState {
    NOT_REQUIRED,
    PENDING_USER_APPROVAL,
    GRANTED_ONCE,
    GRANTED_REUSABLE_RULE,
    DENIED,
    BLOCKED_PROHIBITED_ACTION;

    fun isAuthorized(): Boolean =
        this == NOT_REQUIRED || this == GRANTED_ONCE || this == GRANTED_REUSABLE_RULE
}

@Serializable
data class TessaTaskRevision(
    val revisionId: String = "rev-" + UUID.randomUUID().toString().take(8),
    val taskId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceSender: String,
    val isAuthorizedSource: Boolean,
    val previousInstruction: String,
    val newInstruction: String,
    val changeSummary: String,
    val requiresPermission: Boolean,
    val applied: Boolean,
    val previousStateSnapshot: String
)

@Serializable
data class TessaTaskPlan(
    val classification: String, // CHAT, INFORMATION, TASK, ACTION
    val steps: List<String>,
    val requiredPermissions: List<TessaPermissionType>,
    val targetTool: String,
    val summary: String,
    val relatedProject: String? = null,
    val relatedMessage: String? = null,
    val relatedFile: String? = null
)

@Serializable
data class TessaAuditLogEntry(
    val entryId: String = "audit-" + UUID.randomUUID().toString().take(8),
    val timestamp: Long = System.currentTimeMillis(),
    val userRequest: String,
    val taskId: String,
    val permissionState: String,
    val toolName: String,
    val resultSummary: String
)

object TessaSecretRedactor {
    private val TOKEN_PATTERNS = listOf(
        Regex("gh[pousr]_[A-Za-z0-9_]{16,255}"),
        Regex("github_pat_[A-Za-z0-9_]{16,255}"),
        Regex("ya29\\.[A-Za-z0-9_\\-]{20,255}"),
        Regex("1//[A-Za-z0-9_\\-]{20,255}"),
        Regex("AIza[0-9A-Za-z\\-_]{20,100}"),
        Regex("AQ\\.[A-Za-z0-9_\\-]{10,150}"),
        Regex("sk-[A-Za-z0-9\\-_]{16,120}"),
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{12,}"),
        Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)['\"]?[^\\s'\"]+['\"]?"),
        Regex("(?i)key=[^&\\s\"']+"),
        Regex("(?i)(api[_-]?key|token|secret|password|cvv|upi[_-]?pin|otp)\\s*[:=]\\s*['\"]?[^\\s'\"]{4,}['\"]?")
    )

    fun redact(input: String?, explicitSecrets: List<String> = emptyList()): String {
        if (input.isNullOrBlank()) return ""
        var sanitized: String = input
        val builtInSecrets = buildList {
            addAll(explicitSecrets)
            runCatching { com.cocateail.maya.BuildConfig.TESSA_API_KEY.trim() }.getOrNull()
                ?.takeIf { it.length >= 4 && !it.startsWith("your_", ignoreCase = true) }
                ?.let { add(it) }
            runCatching { com.cocateail.maya.BuildConfig.GEMINI_API_KEY.trim() }.getOrNull()
                ?.takeIf { it.length >= 4 && !it.startsWith("your_", ignoreCase = true) }
                ?.let { add(it) }
        }
        for (secret in builtInSecrets) {
            val trimmed = secret.trim()
            if (trimmed.length >= 4) {
                sanitized = sanitized.replace(trimmed, "[REDACTED_SECRET]")
            }
        }
        for (pattern in TOKEN_PATTERNS) {
            sanitized = pattern.replace(sanitized, "[REDACTED]")
        }
        return sanitized
    }
}

@Serializable
data class TessaTask(
    val taskId: String = "tessa-task-" + UUID.randomUUID().toString().take(12),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val userRequest: String,
    val taskType: TessaTaskState = TessaTaskState.TASK,
    val status: TessaTaskState = TessaTaskState.QUEUED,
    val currentStep: Int = 0,
    val totalSteps: Int = 1,
    val requiresPermission: Boolean = false,
    val permissionState: TessaPermissionState = TessaPermissionState.NOT_REQUIRED,
    val requiredPermissions: List<TessaPermissionType> = emptyList(),
    val targetTool: String = "",
    val result: String? = null,
    val error: String? = null,
    val relatedProject: String? = null,
    val relatedMessage: String? = null,
    val relatedFile: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val timeoutMs: Long = 120_000L,
    val revisions: List<TessaTaskRevision> = emptyList(),
    val previousStateSnapshotJson: String? = null
) {
    fun toEntity(explicitSecrets: List<String> = emptyList()): TessaTaskEntity {
        val json = Json { ignoreUnknownKeys = true }
        return TessaTaskEntity(
            taskId = taskId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            userRequest = TessaSecretRedactor.redact(userRequest, explicitSecrets),
            taskType = taskType.name,
            status = status.name,
            currentStep = currentStep,
            totalSteps = totalSteps.coerceAtLeast(1),
            requiresPermission = requiresPermission,
            permissionState = permissionState.name,
            requiredPermissionsCsv = requiredPermissions.joinToString(",") { it.name },
            targetTool = targetTool,
            result = result?.let { TessaSecretRedactor.redact(it, explicitSecrets) },
            error = error?.let { TessaSecretRedactor.redact(it, explicitSecrets) },
            relatedProject = relatedProject,
            relatedMessage = relatedMessage?.let { TessaSecretRedactor.redact(it, explicitSecrets) },
            relatedFile = relatedFile,
            retryCount = retryCount,
            maxRetries = maxRetries,
            timeoutMs = timeoutMs,
            revisionHistoryJson = json.encodeToString(revisions),
            previousStateSnapshotJson = previousStateSnapshotJson
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromEntity(entity: TessaTaskEntity): TessaTask {
            val parsedType = runCatching { TessaTaskState.valueOf(entity.taskType) }
                .getOrDefault(TessaTaskState.TASK)
            val parsedStatus = runCatching { TessaTaskState.valueOf(entity.status) }
                .getOrDefault(TessaTaskState.QUEUED)
            val parsedPermState = runCatching { TessaPermissionState.valueOf(entity.permissionState) }
                .getOrDefault(TessaPermissionState.NOT_REQUIRED)
            val parsedPerms = entity.requiredPermissionsCsv
                .split(",")
                .mapNotNull { raw ->
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) null else runCatching { TessaPermissionType.valueOf(trimmed) }.getOrNull()
                }
            val parsedRevisions = runCatching {
                json.decodeFromString<List<TessaTaskRevision>>(entity.revisionHistoryJson)
            }.getOrDefault(emptyList())

            return TessaTask(
                taskId = entity.taskId,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                userRequest = entity.userRequest,
                taskType = parsedType,
                status = parsedStatus,
                currentStep = entity.currentStep,
                totalSteps = entity.totalSteps,
                requiresPermission = entity.requiresPermission,
                permissionState = parsedPermState,
                requiredPermissions = parsedPerms,
                targetTool = entity.targetTool,
                result = entity.result,
                error = entity.error,
                relatedProject = entity.relatedProject,
                relatedMessage = entity.relatedMessage,
                relatedFile = entity.relatedFile,
                retryCount = entity.retryCount,
                maxRetries = entity.maxRetries,
                timeoutMs = entity.timeoutMs,
                revisions = parsedRevisions,
                previousStateSnapshotJson = entity.previousStateSnapshotJson
            )
        }
    }
}

// ============================================================================
// TESSA VERIFICATION ENGINE & RESULT REPORTER
// ============================================================================

enum class VerificationStatus {
    PASSED,
    NEEDS_RETRY,
    NEEDS_REPAIR,
    WAITING_FOR_USER_AUTHENTICATION,
    WAITING_FOR_USER_DECISION,
    FAILED
}

@Serializable
data class VerificationDecision(
    val status: VerificationStatus,
    val diagnosticMessage: String,
    val suggestedNextAction: String? = null,
    val confidenceScore: Float = 1.0f,
    val repairPayload: Map<String, String> = emptyMap()
)

object TessaVerificationEngine {

    /**
     * Inspects actual tool outputs, build results, compiler errors, or test runs,
     * and verifies whether the objective succeeded, failed, or requires repair.
     */
    fun verifyExecution(
        toolResult: TessaToolResult,
        expectedGoal: String,
        confidenceThreshold: Float = 0.70f
    ): VerificationDecision {
        // 1. Check for authentication boundaries (e.g. Gmail not connected, OAuth expired, website requested password, OTP, CAPTCHA, 2FA)
        val lowerOutput = toolResult.outputSummary.lowercase()
        val errorLower = toolResult.errorCode?.lowercase().orEmpty()

        if (errorLower.contains("auth_required") ||
            errorLower.contains("waiting_for_user_authentication") ||
            errorLower.contains("gmail_not_connected") ||
            errorLower.contains("oauth_failed") ||
            errorLower.contains("token_expired") ||
            lowerOutput.contains("waiting for user authentication") ||
            lowerOutput.contains("account not connected") ||
            lowerOutput.contains("connect your google account") ||
            lowerOutput.contains("captcha required") ||
            lowerOutput.contains("otp required") ||
            lowerOutput.contains("2fa required") ||
            lowerOutput.contains("two-factor authentication")
        ) {
            return VerificationDecision(
                status = VerificationStatus.WAITING_FOR_USER_AUTHENTICATION,
                diagnosticMessage = if (toolResult.outputSummary.isNotBlank()) {
                    toolResult.outputSummary
                } else {
                    "Service or tool requested authentication / account authorization. Pausing task for user interaction."
                },
                suggestedNextAction = "WAITING_FOR_USER_AUTHENTICATION",
                confidenceScore = 1.0f
            )
        }

        // 2. Check for security or permission blocks
        if (toolResult.status == TessaToolStatus.BLOCKED_SECURITY ||
            toolResult.status == TessaToolStatus.PERMISSION_REQUIRED ||
            errorLower.contains("permission_required")
        ) {
            return VerificationDecision(
                status = VerificationStatus.WAITING_FOR_USER_DECISION,
                diagnosticMessage = toolResult.outputSummary,
                suggestedNextAction = "REQUEST_USER_PERMISSION",
                confidenceScore = 0.5f
            )
        }

        // 3. Check for compiler errors requiring autonomous code repair
        val hasCompilerErrors = lowerOutput.contains("error:") ||
            lowerOutput.contains("compiler error") ||
            lowerOutput.contains("build failed") ||
            toolResult.structuredData.containsKey("compilerError") ||
            toolResult.structuredData.containsKey("errorFile")

        if (hasCompilerErrors) {
            val errorDetails = toolResult.structuredData["compilerError"]
                ?: toolResult.outputSummary.lines().firstOrNull { it.contains("error", ignoreCase = true) }
                ?: "Build failed with compiler errors"

            return VerificationDecision(
                status = VerificationStatus.NEEDS_REPAIR,
                diagnosticMessage = "Compiler error detected: $errorDetails",
                suggestedNextAction = "REPAIR_CODE_AND_REBUILD",
                confidenceScore = 0.85f,
                repairPayload = mapOf(
                    "compilerError" to errorDetails,
                    "targetFile" to (toolResult.structuredData["errorFile"] ?: ""),
                    "errorLine" to (toolResult.structuredData["errorLine"] ?: "0")
                )
            )
        }

        // 4. Check for independently verified success with conclusive evidence
        if (toolResult.isSuccess && toolResult.status == TessaToolStatus.SUCCESS && !hasCompilerErrors) {
            return VerificationDecision(
                status = VerificationStatus.PASSED,
                diagnosticMessage = "Operation verified successfully: ${toolResult.outputSummary.take(120)}",
                suggestedNextAction = "COMPLETE_OR_NEXT_STEP",
                confidenceScore = 0.95f
            )
        }

        // 5. If tool explicitly failed or is unavailable
        if (toolResult.status == TessaToolStatus.FAILED || toolResult.status == TessaToolStatus.TOOL_UNAVAILABLE) {
            return VerificationDecision(
                status = VerificationStatus.FAILED,
                diagnosticMessage = toolResult.outputSummary,
                suggestedNextAction = "NOTIFY_FAILURE",
                confidenceScore = 1.0f
            )
        }

        return VerificationDecision(
            status = VerificationStatus.WAITING_FOR_USER_DECISION,
            diagnosticMessage = "Tool execution did not produce conclusive success evidence: ${toolResult.outputSummary}",
            suggestedNextAction = "USER_DECISION_REQUIRED",
            confidenceScore = 0.4f
        )
    }
}

object TessaResultReporter {

    fun formatReport(
        task: TessaTask,
        verification: VerificationDecision,
        auditTrail: List<TessaAuditLogEntry> = emptyList(),
        explicitSecrets: List<String> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.append("### ⚡ TESSA Task Report: `${task.taskId}`\n\n")
        sb.append("**Objective:** ").append(TessaSecretRedactor.redact(task.userRequest, explicitSecrets)).append("\n")
        sb.append("**Status:** ").append(task.status.name).append(" (Verification: ").append(verification.status.name).append(")\n")
        sb.append("**Tool:** ").append(task.targetTool.ifBlank { "N/A" }).append("\n")

        if (!task.relatedProject.isNullOrBlank()) {
            sb.append("**Project:** `").append(task.relatedProject).append("`\n")
        }

        sb.append("\n**Diagnostic Summary:**\n")
        sb.append(TessaSecretRedactor.redact(verification.diagnosticMessage, explicitSecrets)).append("\n\n")

        if (task.result != null) {
            sb.append("**Output:**\n")
            sb.append(TessaSecretRedactor.redact(task.result, explicitSecrets)).append("\n\n")
        }

        if (task.error != null) {
            sb.append("**Error:**\n")
            sb.append(TessaSecretRedactor.redact(task.error, explicitSecrets)).append("\n\n")
        }

        if (auditTrail.isNotEmpty()) {
            val relevantLogs = auditTrail.filter { it.taskId == task.taskId }.takeLast(3)
            if (relevantLogs.isNotEmpty()) {
                sb.append("**Recent Audit Log:**\n")
                for (log in relevantLogs) {
                    sb.append("• [").append(log.permissionState).append("] ").append(log.toolName).append(": ")
                        .append(TessaSecretRedactor.redact(log.resultSummary, explicitSecrets)).append("\n")
                }
            }
        }

        return sb.toString()
    }
}
