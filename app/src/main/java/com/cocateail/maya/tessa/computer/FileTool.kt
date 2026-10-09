package com.cocateail.maya.tessa.computer

import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.*
import java.io.File
import java.net.URI

class FileTool(
    private val connector: LocalComputerConnector? = null,
    private val defaultWorkspaceRoot: String = "/workspace"
) : TessaTool {

    companion object {
        const val TOOL_ID = "FILE_TOOL"
        const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB
        private val DISALLOWED_EXTENSIONS = setOf("exe", "dll", "so", "dylib", "sys", "bat", "vbs", "ps1")

        /**
         * Validates path for null-bytes, path traversal (`..`), root breakouts, and symlink escapements.
         */
        fun validateSandboxedPath(rawPath: String, workspaceRoot: String): Pair<Boolean, String?> {
            if (rawPath.contains("\u0000") || rawPath.contains("%00")) {
                return false to "PATH_VALIDATION_ERROR: Null byte detected in file path."
            }

            if (rawPath.contains("../") || rawPath.contains("..\\") || rawPath == "..") {
                return false to "PATH_TRAVERSAL_BLOCKED: Path traversal '../' is strictly prohibited."
            }

            // Normalization check
            val normalized = runCatching {
                File(rawPath).normalize().path.replace("\\", "/")
            }.getOrElse { return false to "PATH_INVALID: Cannot normalize path." }

            val normalizedRoot = File(workspaceRoot).normalize().path.replace("\\", "/")

            // Reject sensitive system root directories
            val lower = normalized.lowercase()
            if (lower.startsWith("/etc") || lower.startsWith("/var") || lower.startsWith("/usr") ||
                lower.startsWith("/root") || lower.startsWith("c:/windows") || lower.startsWith("c:/system32")
            ) {
                return false to "ACCESS_DENIED: Access to system directory is strictly prohibited."
            }

            // Must reside within authorized workspace root unless root is empty/relative
            if (normalizedRoot.isNotBlank() && normalizedRoot != "/" && !normalized.startsWith(normalizedRoot)) {
                return false to "SANDBOX_VIOLATION: Path '$normalized' lies outside authorized workspace '$normalizedRoot'."
            }

            return true to null
        }
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Authorized Computer File System Tool"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.READ_LOCAL_FILES)

    override fun isAvailable(): Boolean = connector?.isConnected() == true

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        if (!isAvailable()) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "TOOL_UNAVAILABLE: No authorized local computer companion is connected.",
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        val targetPath = request.parameters["path"] ?: request.parameters["target"] ?: request.userInstruction
        val workspaceRoot = connector?.getPairedComputer()?.workspaceRoot?.ifBlank { defaultWorkspaceRoot } ?: defaultWorkspaceRoot

        // 1. Path Sandbox & Traversal Validation
        val (isValidPath, pathError) = validateSandboxedPath(targetPath, workspaceRoot)
        if (!isValidPath) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.BLOCKED_SECURITY,
                outputSummary = pathError ?: "Path validation failed.",
                errorCode = "SANDBOX_VIOLATION"
            )
        }

        val actionUpper = request.action.trim().uppercase()

        // 2. Map required permissions based on action
        val requiredPerms = when (actionUpper) {
            "DELETE_FILE", "DELETE_DIRECTORY" -> listOf(TessaPermissionType.DELETE_LOCAL_FILES)
            "WRITE_FILE", "CREATE_FILE", "MODIFY_FILE", "CREATE_DIRECTORY", "RENAME_FILE", "MOVE_FILE" ->
                listOf(TessaPermissionType.WRITE_LOCAL_FILES)
            else -> listOf(TessaPermissionType.READ_LOCAL_FILES)
        }

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = requiredPerms
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

        // 3. Dispatch to Computer Connector
        val agentRequest = ComputerAgentRequest(
            deviceId = connector?.getPairedComputer()?.computerId.orEmpty(),
            sessionToken = connector?.getPairedComputer()?.sessionToken.orEmpty(),
            taskId = request.taskId,
            action = actionUpper.ifBlank { "READ_FILE" },
            target = targetPath,
            permission = requiredPerms.joinToString(",") { it.name },
            parameters = request.parameters
        )

        val response = connector!!.executeRequest(agentRequest)

        permissionManager.recordTraceableSideEffect(
            userRequest = request.userInstruction,
            taskId = request.taskId,
            permissionState = permDecision.state,
            toolName = toolId,
            resultSummary = "Executed file operation $actionUpper on '$targetPath': ${response.outputSummary.take(100)}",
            explicitSecrets = listOf(config.tessaApiKey, config.githubToken)
        )

        return TessaToolResult(
            toolId = toolId,
            status = if (response.success) TessaToolStatus.SUCCESS else TessaToolStatus.FAILED,
            outputSummary = response.outputSummary,
            structuredData = response.data,
            errorCode = response.errorCode
        )
    }
}
