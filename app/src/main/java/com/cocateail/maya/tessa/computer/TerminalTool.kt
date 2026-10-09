package com.cocateail.maya.tessa.computer

import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.*

class TerminalTool(
    private val connector: LocalComputerConnector? = null,
    private val defaultWorkspaceRoot: String = "/workspace"
) : TessaTool {

    companion object {
        const val TOOL_ID = "TERMINAL_TOOL"
        const val MAX_OUTPUT_CHARS = 65_536 // 64 KB clean cap

        private val ALLOWED_BINARIES = setOf(
            "git", "cargo", "gradle", "cmake", "msbuild", "dotnet",
            "npm", "pnpm", "yarn", "python3", "python", "node", "npx",
            "unreal", "make", "pytest", "go", "rustc",
            "dir", "ls", "cat", "head", "tail", "echo", "mkdir", "diff"
        )

        private val PROHIBITED_SUBSTRINGS = listOf(
            "rm -rf /", "rm -rf *", "mkfs", "dd if=", ":(){ :|:& };:",
            "curl", "wget", "| sh", "| bash", "eval", "sudo", "su -",
            "format c:", "del /f /s /q c:", "attrib -h -r -s",
            "passwd", "shadow", "id_rsa", ".ssh", "sam", "lsass"
        )

        fun isCommandAllowed(commandLine: String): Pair<Boolean, String?> {
            val clean = commandLine.trim()
            if (clean.isBlank()) return false to "COMMAND_EMPTY: Empty command line."

            val lower = clean.lowercase()

            for (prohibited in PROHIBITED_SUBSTRINGS) {
                if (lower.contains(prohibited)) {
                    return false to "DANGEROUS_COMMAND_BLOCKED: Prohibited command pattern '$prohibited' detected."
                }
            }

            // Extract primary binary name
            val binary = clean.split(Regex("\\s+")).firstOrNull()?.lowercase() ?: ""
            val sanitizedBinary = binary.substringAfterLast("/").substringAfterLast("\\").removeSuffix(".exe")

            if (!ALLOWED_BINARIES.contains(sanitizedBinary)) {
                return false to "COMMAND_NOT_ALLOWLISTED: Command '$sanitizedBinary' is not in the safe terminal allowlist."
            }

            return true to null
        }
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Safe Paired Terminal Tool"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.RUN_TERMINAL)

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
                outputSummary = "TOOL_UNAVAILABLE: No paired computer is connected to execute terminal commands.",
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        val rawCommand = request.parameters["command"] ?: request.userInstruction
        val (allowed, blockReason) = isCommandAllowed(rawCommand)
        if (!allowed) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.BLOCKED_SECURITY,
                outputSummary = blockReason ?: "Command rejected by terminal security policy.",
                errorCode = "PROHIBITED_TERMINAL_COMMAND"
            )
        }

        // Require explicit RUN_TERMINAL permission
        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = "Execute command: $rawCommand",
            requiredPermissions = listOf(TessaPermissionType.RUN_TERMINAL)
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

        val timeoutSec = request.parameters["timeoutSec"] ?: "60"
        val workingDir = request.parameters["cwd"] ?: defaultWorkspaceRoot

        val agentRequest = ComputerAgentRequest(
            deviceId = connector?.getPairedComputer()?.computerId.orEmpty(),
            sessionToken = connector?.getPairedComputer()?.sessionToken.orEmpty(),
            taskId = request.taskId,
            action = "TERMINAL_EXEC",
            target = workingDir,
            permission = TessaPermissionType.RUN_TERMINAL.name,
            parameters = mapOf(
                "command" to rawCommand,
                "cwd" to workingDir,
                "timeoutSec" to timeoutSec
            )
        )

        val response = connector!!.executeRequest(agentRequest)

        // Sanitize and redact output
        val sanitizedOutput = TessaSecretRedactor.redact(
            response.outputSummary.take(MAX_OUTPUT_CHARS),
            listOf(config.tessaApiKey, config.githubToken, config.customApiKey)
        )

        permissionManager.recordTraceableSideEffect(
            userRequest = "Terminal command: $rawCommand",
            taskId = request.taskId,
            permissionState = permDecision.state,
            toolName = toolId,
            resultSummary = "Ran '$rawCommand' in $workingDir; exit=${if (response.success) 0 else 1}",
            explicitSecrets = listOf(config.tessaApiKey, config.githubToken)
        )

        return TessaToolResult(
            toolId = toolId,
            status = if (response.success) TessaToolStatus.SUCCESS else TessaToolStatus.FAILED,
            outputSummary = sanitizedOutput,
            structuredData = response.data,
            errorCode = response.errorCode
        )
    }
}
