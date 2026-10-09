package com.cocateail.maya.tessa.computer

import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UnrealEngineInfo(
    val isInstalled: Boolean,
    val engineVersion: String = "",
    val installPath: String = "",
    val ubtPath: String = "",
    val editorExecutable: String = ""
)

@Serializable
data class UnrealBuildError(
    val file: String,
    val line: Int,
    val column: Int = 0,
    val errorCode: String,
    val message: String
)

@Serializable
data class UnrealProjectDescriptor(
    val projectName: String,
    val projectPath: String,
    val uprojectFilePath: String,
    val engineAssociation: String = "5.4",
    val modules: List<String> = listOf(projectName)
)

interface UnrealEngineConnector {
    fun isAvailable(): Boolean
    suspend fun detectUnreal(): Result<UnrealEngineInfo>
    suspend fun createProject(projectName: String, targetFolder: String, engineVersion: String = "5.4"): Result<UnrealProjectDescriptor>
    suspend fun createCppClass(projectName: String, className: String, baseClass: String, isActor: Boolean): Result<Pair<String, String>>
    suspend fun buildProject(projectName: String, projectPath: String, config: String = "Development Editor"): Result<Pair<Boolean, String>>
    suspend fun parseBuildErrors(buildLog: String): List<UnrealBuildError>
    suspend fun repairCode(projectName: String, error: UnrealBuildError, sourceCode: String): Pair<Boolean, String>
    suspend fun launchProject(projectName: String, projectPath: String): Result<Boolean>
}

class RealUnrealEngineConnector(
    private val computerConnector: LocalComputerConnector? = null
) : UnrealEngineConnector {

    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean =
        computerConnector?.isConnected() == true &&
            computerConnector.getPairedComputer()?.capabilities?.contains(ComputerCapability.UNREAL_ENGINE) == true

    override suspend fun detectUnreal(): Result<UnrealEngineInfo> {
        val conn = computerConnector ?: return Result.failure(
            ComputerException("TOOL_UNAVAILABLE", "No paired computer connected.")
        )
        val resp = conn.executeRequest(
            ComputerAgentRequest(
                deviceId = conn.getPairedComputer()?.computerId.orEmpty(),
                sessionToken = conn.getPairedComputer()?.sessionToken.orEmpty(),
                taskId = "detect-unreal",
                action = "UNREAL_DETECT"
            )
        )
        if (!resp.success) {
            return Result.failure(ComputerException(resp.errorCode ?: "UNREAL_NOT_FOUND", resp.outputSummary))
        }

        val raw = resp.data["unrealInfoJson"]
        if (raw != null) {
            val info = runCatching { json.decodeFromString<UnrealEngineInfo>(raw) }.getOrNull()
            if (info != null) return Result.success(info)
        }

        return Result.success(
            UnrealEngineInfo(
                isInstalled = true,
                engineVersion = resp.data["engineVersion"] ?: "5.4",
                installPath = resp.data["installPath"] ?: "C:/Program Files/Epic Games/UE_5.4",
                ubtPath = resp.data["ubtPath"] ?: "Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool.dll"
            )
        )
    }

    override suspend fun createProject(
        projectName: String,
        targetFolder: String,
        engineVersion: String
    ): Result<UnrealProjectDescriptor> {
        val conn = computerConnector ?: return Result.failure(
            ComputerException("TOOL_UNAVAILABLE", "No paired computer connected.")
        )
        val resp = conn.executeRequest(
            ComputerAgentRequest(
                deviceId = conn.getPairedComputer()?.computerId.orEmpty(),
                sessionToken = conn.getPairedComputer()?.sessionToken.orEmpty(),
                taskId = "create-ue-proj",
                action = "UNREAL_CREATE_PROJECT",
                target = "$targetFolder/$projectName",
                parameters = mapOf(
                    "projectName" to projectName,
                    "targetFolder" to targetFolder,
                    "engineVersion" to engineVersion
                )
            )
        )
        if (!resp.success) {
            return Result.failure(ComputerException(resp.errorCode ?: "CREATE_FAILED", resp.outputSummary))
        }

        val uprojectPath = "$targetFolder/$projectName/$projectName.uproject"
        return Result.success(
            UnrealProjectDescriptor(
                projectName = projectName,
                projectPath = "$targetFolder/$projectName",
                uprojectFilePath = uprojectPath,
                engineAssociation = engineVersion
            )
        )
    }

    override suspend fun createCppClass(
        projectName: String,
        className: String,
        baseClass: String,
        isActor: Boolean
    ): Result<Pair<String, String>> {
        val conn = computerConnector ?: return Result.failure(
            ComputerException("TOOL_UNAVAILABLE", "No paired computer connected.")
        )
        val resp = conn.executeRequest(
            ComputerAgentRequest(
                deviceId = conn.getPairedComputer()?.computerId.orEmpty(),
                sessionToken = conn.getPairedComputer()?.sessionToken.orEmpty(),
                taskId = "create-cpp-$className",
                action = "UNREAL_CREATE_CPP_CLASS",
                parameters = mapOf(
                    "projectName" to projectName,
                    "className" to className,
                    "baseClass" to baseClass,
                    "isActor" to isActor.toString()
                )
            )
        )
        if (!resp.success) {
            return Result.failure(ComputerException(resp.errorCode ?: "CPP_CLASS_FAILED", resp.outputSummary))
        }
        val headerPath = resp.data["headerFile"] ?: "Source/$projectName/$className.h"
        val cppPath = resp.data["cppFile"] ?: "Source/$projectName/$className.cpp"
        return Result.success(headerPath to cppPath)
    }

    override suspend fun buildProject(
        projectName: String,
        projectPath: String,
        config: String
    ): Result<Pair<Boolean, String>> {
        val conn = computerConnector ?: return Result.failure(
            ComputerException("TOOL_UNAVAILABLE", "No paired computer connected.")
        )
        val resp = conn.executeRequest(
            ComputerAgentRequest(
                deviceId = conn.getPairedComputer()?.computerId.orEmpty(),
                sessionToken = conn.getPairedComputer()?.sessionToken.orEmpty(),
                taskId = "build-$projectName",
                action = "UNREAL_BUILD",
                target = projectPath,
                parameters = mapOf(
                    "projectName" to projectName,
                    "projectPath" to projectPath,
                    "config" to config
                )
            )
        )
        return Result.success(resp.success to resp.outputSummary)
    }

    override suspend fun parseBuildErrors(buildLog: String): List<UnrealBuildError> {
        return parseCompilerErrorsFromLog(buildLog)
    }

    override suspend fun repairCode(
        projectName: String,
        error: UnrealBuildError,
        sourceCode: String
    ): Pair<Boolean, String> {
        val repaired = repairSourceCode(error, sourceCode)
        return true to repaired
    }

    override suspend fun launchProject(projectName: String, projectPath: String): Result<Boolean> {
        val conn = computerConnector ?: return Result.failure(
            ComputerException("TOOL_UNAVAILABLE", "No paired computer connected.")
        )
        val resp = conn.executeRequest(
            ComputerAgentRequest(
                deviceId = conn.getPairedComputer()?.computerId.orEmpty(),
                sessionToken = conn.getPairedComputer()?.sessionToken.orEmpty(),
                taskId = "launch-$projectName",
                action = "UNREAL_LAUNCH",
                target = projectPath
            )
        )
        return Result.success(resp.success)
    }

    companion object {
        private val MSVC_ERROR_REGEX = Regex("^(.*?)\\((\\d+)(?:,(\\d+))?\\):\\s*error\\s*([A-Za-z0-9]+):\\s*(.*)$", RegexOption.MULTILINE)
        private val CLANG_ERROR_REGEX = Regex("^(.*?):(\\d+):(\\d+):\\s*error:\\s*(.*)$", RegexOption.MULTILINE)

        fun parseCompilerErrorsFromLog(log: String): List<UnrealBuildError> {
            val errors = mutableListOf<UnrealBuildError>()

            for (match in MSVC_ERROR_REGEX.findAll(log)) {
                val file = match.groupValues[1].trim()
                val line = match.groupValues[2].toIntOrNull() ?: 1
                val col = match.groupValues[3].toIntOrNull() ?: 1
                val code = match.groupValues[4].trim()
                val msg = match.groupValues[5].trim()
                errors.add(UnrealBuildError(file, line, col, code, msg))
            }

            for (match in CLANG_ERROR_REGEX.findAll(log)) {
                val file = match.groupValues[1].trim()
                val line = match.groupValues[2].toIntOrNull() ?: 1
                val col = match.groupValues[3].toIntOrNull() ?: 1
                val msg = match.groupValues[4].trim()
                errors.add(UnrealBuildError(file, line, col, "CLANG_ERROR", msg))
            }

            return errors
        }

        fun repairSourceCode(error: UnrealBuildError, originalCode: String): String {
            val msgLower = error.message.lowercase()
            val lines = originalCode.lines().toMutableList()

            // 1. Missing include (e.g. undeclared identifier 'UGameplayStatics' or 'FVector' or 'FMath')
            if (msgLower.contains("undeclared identifier") || msgLower.contains("use of undeclared identifier")) {
                if (msgLower.contains("ugameplaystatics") && !originalCode.contains("#include \"Kismet/GameplayStatics.h\"")) {
                    lines.add(0, "#include \"Kismet/GameplayStatics.h\"")
                } else if (msgLower.contains("fmath") && !originalCode.contains("#include \"Math/UnrealMathUtility.h\"")) {
                    lines.add(0, "#include \"Math/UnrealMathUtility.h\"")
                }
            }

            // 2. Missing GENERATED_BODY()
            if (msgLower.contains("generated_body") || msgLower.contains("expected 'generated_body'")) {
                val classIdx = lines.indexOfFirst { it.contains("class ") && it.contains(":") }
                if (classIdx != -1 && classIdx + 1 < lines.size) {
                    lines.add(classIdx + 2, "\tGENERATED_BODY()")
                }
            }

            // 3. Missing semicolon at specific line
            if (msgLower.contains("expected ';'") || msgLower.contains("missing ';'")) {
                val targetLineIdx = (error.line - 1).coerceIn(0, lines.size - 1)
                val targetLine = lines[targetLineIdx]
                if (!targetLine.trimEnd().endsWith(";")) {
                    lines[targetLineIdx] = targetLine.trimEnd() + ";"
                }
            }

            return lines.joinToString("\n")
        }
    }
}

class UnrealEngineTool(
    private val connector: UnrealEngineConnector? = null
) : TessaTool {

    companion object {
        const val TOOL_ID = "UNREAL_ENGINE_TOOL"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Unreal Engine 3D/Game Development Tool"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.USE_UNREAL, TessaPermissionType.BUILD_PROJECT)

    override fun isAvailable(): Boolean = connector?.isAvailable() == true

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        if (!isAvailable()) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.TOOL_UNAVAILABLE,
                outputSummary = "TOOL_UNAVAILABLE: Unreal Engine tool is not available on this computer companion.",
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = listOf(TessaPermissionType.USE_UNREAL, TessaPermissionType.BUILD_PROJECT)
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
        val resolvedAction = when {
            action == "DETECT" || request.userInstruction.contains("detect unreal", ignoreCase = true) -> "DETECT"
            action == "CREATE_PROJECT" || request.userInstruction.contains("create project", ignoreCase = true) -> "CREATE_PROJECT"
            action == "BUILD" || action == "EXECUTE" ||
                request.userInstruction.contains("build", ignoreCase = true) ||
                request.userInstruction.contains("compile", ignoreCase = true) ||
                request.userInstruction.contains("repair", ignoreCase = true) -> "BUILD"
            else -> action
        }
        val c = connector!!

        return when (resolvedAction) {
            "DETECT" -> {
                val info = c.detectUnreal()
                if (info.isSuccess) {
                    val ue = info.getOrThrow()
                    TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.SUCCESS,
                        outputSummary = "Detected Unreal Engine ${ue.engineVersion} at ${ue.installPath}",
                        structuredData = mapOf("version" to ue.engineVersion, "path" to ue.installPath)
                    )
                } else {
                    TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.FAILED,
                        outputSummary = "Failed to detect Unreal Engine: ${info.exceptionOrNull()?.message}",
                        errorCode = "UNREAL_NOT_FOUND"
                    )
                }
            }
            "CREATE_PROJECT" -> {
                val projectName = request.parameters["projectName"] ?: "FlightSimulator"
                val folder = request.parameters["folder"] ?: "/workspace/UnrealProjects"
                val created = c.createProject(projectName, folder)
                if (created.isSuccess) {
                    val desc = created.getOrThrow()
                    TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.SUCCESS,
                        outputSummary = "Created Unreal Engine project `${desc.projectName}` at `${desc.projectPath}`",
                        structuredData = mapOf("projectName" to desc.projectName, "projectPath" to desc.projectPath)
                    )
                } else {
                    TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.FAILED,
                        outputSummary = "Could not create project: ${created.exceptionOrNull()?.message}",
                        errorCode = "UNREAL_CREATE_FAILED"
                    )
                }
            }
            "BUILD" -> {
                val projectName = request.parameters["projectName"] ?: "FlightSimulator"
                val path = request.parameters["projectPath"] ?: "/workspace/UnrealProjects/$projectName"
                val buildResult = c.buildProject(projectName, path)
                if (buildResult.isSuccess) {
                    val (ok, log) = buildResult.getOrThrow()
                    if (ok) {
                        TessaToolResult(
                            toolId = toolId,
                            status = TessaToolStatus.SUCCESS,
                            outputSummary = "Unreal build succeeded for $projectName:\n$log",
                            structuredData = mapOf("buildStatus" to "SUCCESS")
                        )
                    } else {
                        val errors = c.parseBuildErrors(log)
                        val topError = errors.firstOrNull()
                        val errList = if (errors.isNotEmpty()) {
                            errors.take(3).joinToString("\n") { "${it.file}(${it.line}): ${it.message}" }
                        } else {
                            log.take(300)
                        }
                        TessaToolResult(
                            toolId = toolId,
                            status = TessaToolStatus.FAILED,
                            outputSummary = "Unreal build failed with ${errors.size} error(s):\n$errList",
                            structuredData = buildMap {
                                put("buildStatus", "FAILED")
                                if (topError != null) {
                                    put("compilerError", topError.message)
                                    put("errorFile", topError.file)
                                    put("errorLine", topError.line.toString())
                                } else {
                                    put("compilerError", log.take(150))
                                }
                            },
                            errorCode = "COMPILER_ERROR"
                        )
                    }
                } else {
                    TessaToolResult(
                        toolId = toolId,
                        status = TessaToolStatus.FAILED,
                        outputSummary = "Build execution failed: ${buildResult.exceptionOrNull()?.message}",
                        structuredData = mapOf("buildStatus" to "FAILED"),
                        errorCode = "BUILD_EXECUTION_FAILED"
                    )
                }
            }
            else -> {
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Unreal tool does not support action '$action'.",
                    errorCode = "UNSUPPORTED_ACTION"
                )
            }
        }
    }
}
