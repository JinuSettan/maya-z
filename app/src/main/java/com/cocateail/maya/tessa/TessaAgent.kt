package com.cocateail.maya.tessa

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cocateail.maya.data.db.AppDatabase
import com.cocateail.maya.data.db.TessaTaskDao
import com.cocateail.maya.data.model.DeveloperConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent store for TessaTask records.
 * Uses Room (`TessaTaskDao`) when provided so tasks survive process restarts,
 * and maintains an in-memory synchronized map for fast state queries & JVM testing.
 * Never persists secrets inside task state.
 */
class TessaTaskStore(
    private val tessaTaskDao: TessaTaskDao? = null
) {
    private val memoryTasks = ConcurrentHashMap<String, TessaTask>()
    private val _tasksFlow = MutableStateFlow<List<TessaTask>>(emptyList())
    val tasksFlow: StateFlow<List<TessaTask>> = _tasksFlow.asStateFlow()

    fun observeTasks(): Flow<List<TessaTask>> {
        return tessaTaskDao?.observeAllTasks()?.map { entities ->
            entities.map { TessaTask.fromEntity(it) }
        } ?: tasksFlow
    }

    suspend fun saveTask(task: TessaTask, explicitSecrets: List<String> = emptyList()): TessaTask {
        // Round-trip through toEntity() so all secret redaction rules are strictly enforced
        val sanitizedEntity = task.toEntity(explicitSecrets)
        val sanitizedTask = TessaTask.fromEntity(sanitizedEntity)
        memoryTasks[sanitizedTask.taskId] = sanitizedTask
        tessaTaskDao?.upsertTask(sanitizedEntity)
        refreshFlowSnapshot()
        return sanitizedTask
    }

    suspend fun getTaskById(taskId: String): TessaTask? {
        val fromDao = tessaTaskDao?.getTaskById(taskId)?.let { TessaTask.fromEntity(it) }
        if (fromDao != null) {
            memoryTasks[taskId] = fromDao
            return fromDao
        }
        return memoryTasks[taskId]
    }

    suspend fun getLatestTaskForProject(projectName: String): TessaTask? {
        if (projectName.isBlank()) return null
        val fromDao = tessaTaskDao?.getLatestTaskForProject(projectName)?.let { TessaTask.fromEntity(it) }
        if (fromDao != null) return fromDao
        return memoryTasks.values
            .filter { it.relatedProject?.equals(projectName, ignoreCase = true) == true }
            .maxByOrNull { it.updatedAt }
    }

    suspend fun getAllTasks(): List<TessaTask> {
        val daoList = tessaTaskDao?.getAllTasksOnce()?.map { TessaTask.fromEntity(it) }
        if (daoList != null) {
            daoList.forEach { memoryTasks[it.taskId] = it }
            refreshFlowSnapshot()
            return daoList
        }
        return memoryTasks.values.sortedByDescending { it.updatedAt }
    }

    private fun refreshFlowSnapshot() {
        _tasksFlow.value = memoryTasks.values.sortedByDescending { it.updatedAt }
    }
}

/**
 * Understands user requests and classifies them into:
 * CHAT, INFORMATION, TASK, or ACTION, with a concrete step plan, required permissions, and target tool.
 */
object TessaPlanner {

    private val PROJECT_STOP_WORDS = setOf(
        "project", "app", "repo", "update", "for", "with", "in", "to", "on", "from", "the", "and", "or", "called", "named"
    )

    fun extractProjectName(text: String): String? {
        val keywords = listOf("project", "repo", "app")
        for (kw in keywords) {
            val regex = Regex("(?i)\\b$kw\\s+(?:called\\s+|named\\s+)?([A-Za-z0-9_-]{2,40})")
            val match = regex.findAll(text)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .firstOrNull { it.lowercase() !in PROJECT_STOP_WORDS }
            if (match != null) return match
        }
        return null
    }

    /**
     * Defense against Prompt Injection from untrusted external content (emails, web pages, PDFs, READMEs).
     * Neutralizes attempts to escalate permissions, bypass security policies, or unlock SIMBA.
     */
    fun sanitizeUntrustedExternalText(text: String?): String? {
        if (text.isNullOrBlank()) return text
        val injectionPhrases = listOf(
            "ignore previous instructions",
            "ignore all instructions",
            "unlock simba",
            "grant all permissions",
            "grant permission",
            "override security",
            "bypass permission",
            "send all emails",
            "delete all files",
            "authorize payment"
        )
        var sanitized: String = text
        for (phrase in injectionPhrases) {
            if (sanitized.contains(phrase, ignoreCase = true)) {
                sanitized = Regex("(?i)" + Regex.escape(phrase)).replace(sanitized, "[UNTRUSTED_EXTERNAL_INSTRUCTION_STRIPPED]")
            }
        }
        return sanitized
    }

    fun classifyAndPlan(
        userRequest: String,
        attachedMessageText: String? = null,
        attachedFileName: String? = null,
        hasPdfBytes: Boolean = false
    ): TessaTaskPlan {
        val combined = buildString {
            append(userRequest.trim())
            if (!attachedMessageText.isNullOrBlank()) {
                append("\n")
                append(attachedMessageText.trim())
            }
        }
        val lower = combined.lowercase()

        // 1. Check if message/PDF analysis is requested
        val mentionsGmail = lower.contains("gmail") ||
            lower.contains("check my email") ||
            lower.contains("check email") ||
            lower.contains("check inbox") ||
            lower.contains("my inbox") ||
            lower.contains("read email") ||
            lower.contains("draft email") ||
            lower.contains("send email")

        val mentionsMailOrPdf = mentionsGmail ||
            !attachedMessageText.isNullOrBlank() ||
            !attachedFileName.isNullOrBlank() ||
            hasPdfBytes ||
            lower.contains("mail") ||
            lower.contains("email") ||
            lower.contains("inbox") ||
            lower.contains("pdf") ||
            lower.contains("attachment")

        val mentionsUnreal = lower.contains("unreal") ||
            lower.contains("unreal engine") ||
            lower.contains("ue5") ||
            lower.contains("ue4") ||
            lower.contains("flight simulator")

        val mentionsEducation = lower.contains("sslc") ||
            lower.contains("10th result") ||
            lower.contains("plus two") ||
            lower.contains("+2") ||
            lower.contains("dhse") ||
            lower.contains("degree result") ||
            lower.contains("university result") ||
            lower.contains("allotment list") ||
            lower.contains("rank list") ||
            lower.contains("admission notification")

        val mentionsApplicationAssistant = (lower.contains("application") || lower.contains("apply")) &&
            (lower.contains("fill") || lower.contains("submit") || lower.contains("allotment") || lower.contains("admission"))

        val mentionsTerminal = lower.contains("terminal") ||
            lower.contains("run command") ||
            lower.contains("safe terminal") ||
            lower.contains("execute command")

        val mentionsLocalFiles = lower.contains("local file") ||
            lower.contains("file on computer") ||
            lower.contains("workspace file") ||
            (lower.contains("read file") && !lower.contains("email")) ||
            lower.contains("delete file") ||
            lower.contains("create file")

        val mentionsBrowser = lower.contains("browser") ||
            lower.contains("open url") ||
            lower.contains("browse ") ||
            lower.contains("website")

        val mentionsBuildOrProject = lower.contains("build") ||
            lower.contains("project update") ||
            lower.contains("update project") ||
            lower.contains("create app") ||
            lower.contains("android app") ||
            lower.contains("maya builder") ||
            lower.contains("fix bug") ||
            lower.contains("implement") ||
            lower.contains("scaffold")

        val mentionsDesktopComputer = lower.contains("local computer") ||
            lower.contains("desktop") ||
            lower.contains("laptop") ||
            lower.contains("open browser on pc") ||
            lower.contains("run on my pc")

        val mentionsShopping = lower.contains("amazon") ||
            lower.contains("flipkart") ||
            lower.contains("buy ") ||
            lower.contains("order ") ||
            lower.contains("place order") ||
            lower.contains("compare product") ||
            lower.contains("check delivery")

        val mentionsLocationOrAddress = lower.contains("location") ||
            lower.contains("pin code") ||
            lower.contains("pincode") ||
            lower.contains("delivery address") ||
            lower.contains("save address") ||
            lower.contains("house number")

        val mentionsPayment = lower.contains("pay ") ||
            lower.contains("payment") ||
            lower.contains("upi") ||
            lower.contains("cvv") ||
            lower.contains("otp")

        val detectedProject = extractProjectName(combined)

        return when {
            mentionsPayment -> {
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf("UNDERSTAND_REQUEST", "SECURITY_AND_PAYMENT_POLICY_CHECK", "BLOCK_AUTONOMOUS_PAYMENT"),
                    requiredPermissions = listOf(TessaPermissionType.USE_PAYMENT),
                    targetTool = ShoppingTool.TOOL_ID,
                    summary = "Payment or OTP action requested — must be blocked from autonomous execution.",
                    relatedProject = detectedProject
                )
            }

            // Gmail -> TESSA -> Unreal Engine / Computer Handoff
            mentionsMailOrPdf && mentionsUnreal -> {
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf(
                        "READ_EMAIL",
                        "IDENTIFY_PROJECT",
                        "EXTRACT_CHANGES",
                        "CHECK_PERMISSIONS",
                        "UPDATE_UNREAL_PROJECT",
                        "BUILD_PROJECT",
                        "VERIFY_RESULT",
                        "REPORT_RESULT"
                    ),
                    requiredPermissions = listOf(
                        TessaPermissionType.READ_EMAIL,
                        TessaPermissionType.USE_UNREAL,
                        TessaPermissionType.BUILD_PROJECT
                    ),
                    targetTool = com.cocateail.maya.tessa.computer.UnrealEngineTool.TOOL_ID,
                    summary = "Read authorized email and update/build Unreal Engine project on paired computer.",
                    relatedProject = detectedProject ?: "FlightSimulator"
                )
            }

            mentionsUnreal -> {
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf(
                        "UNDERSTAND_GOAL",
                        "CHECK_COMPUTER_CONNECTION",
                        "CHECK_UNREAL",
                        "CREATE_OR_INSPECT_PROJECT",
                        "CREATE_FILES",
                        "BUILD_PROJECT",
                        "PARSE_ERRORS_AND_REPAIR",
                        "VERIFY_BUILD",
                        "REPORT_RESULT"
                    ),
                    requiredPermissions = listOf(
                        TessaPermissionType.USE_UNREAL,
                        TessaPermissionType.BUILD_PROJECT
                    ),
                    targetTool = com.cocateail.maya.tessa.computer.UnrealEngineTool.TOOL_ID,
                    summary = "Create, code, build, and verify 3D/game project in Unreal Engine.",
                    relatedProject = detectedProject ?: "FlightSimulator"
                )
            }

            mentionsEducation -> {
                TessaTaskPlan(
                    classification = TessaTaskState.TASK.name,
                    steps = listOf(
                        "IDENTIFY_OFFICIAL_SOURCE",
                        "OPEN_WEBSITE",
                        "VERIFY_OFFICIAL_DOMAIN",
                        "SEARCH_RESULT",
                        "VERIFY_RESULT",
                        "REPORT_STRUCTURED_ANSWER"
                    ),
                    requiredPermissions = listOf(TessaPermissionType.USE_BROWSER),
                    targetTool = com.cocateail.maya.tessa.computer.EducationResearchTool.TOOL_ID,
                    summary = "Research official examination/education portal and report verified result."
                )
            }

            mentionsApplicationAssistant -> {
                val perms = if (lower.contains("submit")) {
                    listOf(TessaPermissionType.SUBMIT_APPLICATION)
                } else {
                    emptyList()
                }
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf(
                        "READ_NOTIFICATION",
                        "DETERMINE_ELIGIBILITY",
                        "EXTRACT_REQUIRED_DOCUMENTS",
                        "PREPARE_APPLICATION_DATA",
                        "FILL_SAFE_FIELDS",
                        "REVIEW_ENTERED_INFO",
                        "SHOW_FINAL_PREVIEW_AND_CONFIRM"
                    ),
                    requiredPermissions = perms,
                    targetTool = com.cocateail.maya.tessa.computer.ApplicationAssistantTool.TOOL_ID,
                    summary = "Admission/allotment application assistant. Final submission requires explicit confirmation."
                )
            }

            mentionsTerminal -> {
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf(
                        "CHECK_TERMINAL_PERMISSION",
                        "CHECK_COMMAND_ALLOWLIST",
                        "EXECUTE_SAFE_COMMAND",
                        "REDACT_SECRETS",
                        "REPORT_OUTPUT"
                    ),
                    requiredPermissions = listOf(TessaPermissionType.RUN_TERMINAL),
                    targetTool = com.cocateail.maya.tessa.computer.TerminalTool.TOOL_ID,
                    summary = "Execute command in safe terminal on paired computer."
                )
            }

            mentionsLocalFiles -> {
                val perms = when {
                    lower.contains("delete") -> listOf(TessaPermissionType.DELETE_LOCAL_FILES)
                    lower.contains("create") || lower.contains("write") || lower.contains("modify") ->
                        listOf(TessaPermissionType.WRITE_LOCAL_FILES)
                    else -> listOf(TessaPermissionType.READ_LOCAL_FILES)
                }
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf("VALIDATE_SANDBOX_PATH", "CHECK_PERMISSIONS", "EXECUTE_FILE_OP", "REPORT_RESULT"),
                    requiredPermissions = perms,
                    targetTool = com.cocateail.maya.tessa.computer.FileTool.TOOL_ID,
                    summary = "Execute sandboxed file operation on paired computer."
                )
            }

            mentionsBrowser -> {
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf("CHECK_BROWSER_PERMISSION", "OPEN_URL_OR_SEARCH", "INSPECT_PAGE_STATE", "REPORT_RESULT"),
                    requiredPermissions = listOf(TessaPermissionType.USE_BROWSER),
                    targetTool = com.cocateail.maya.tessa.computer.BrowserTool.TOOL_ID,
                    summary = "Execute authorized web browser action."
                )
            }

            mentionsGmail && lower.contains("send email") -> {
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf("AUTHENTICATE_GMAIL", "CONFIRM_RECIPIENT_AND_CONTENT", "SEND_EMAIL", "REPORT_RESULT"),
                    requiredPermissions = listOf(TessaPermissionType.SEND_EMAIL),
                    targetTool = com.cocateail.maya.tessa.gmail.GmailTool.TOOL_ID,
                    summary = "Send an email via connected Gmail account (requires explicit user approval)."
                )
            }

            mentionsGmail && (lower.contains("draft") || lower.contains("create draft")) -> {
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf("AUTHENTICATE_GMAIL", "PREPARE_DRAFT", "SAVE_GMAIL_DRAFT", "REPORT_RESULT"),
                    requiredPermissions = listOf(TessaPermissionType.DRAFT_EMAIL),
                    targetTool = com.cocateail.maya.tessa.gmail.GmailTool.TOOL_ID,
                    summary = "Prepare and create an email draft in connected Gmail account."
                )
            }

            mentionsGmail && mentionsBuildOrProject -> {
                val perms = buildList {
                    add(TessaPermissionType.READ_EMAIL)
                    add(TessaPermissionType.READ_ATTACHMENT)
                    add(TessaPermissionType.READ_PDF)
                    add(TessaPermissionType.WRITE_PROJECT)
                    if (lower.contains("push") && lower.contains("github")) {
                        add(TessaPermissionType.PUSH_GITHUB)
                    }
                }
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf(
                        "READ_GMAIL_MESSAGE",
                        "DOWNLOAD_ATTACHMENT",
                        "ANALYZE_INSTRUCTIONS",
                        "REQUEST_PERMISSION",
                        "HANDOFF_TO_MAYA_BUILDER",
                        "TRACK_RESULT",
                        "REPORT_RESULT"
                    ),
                    requiredPermissions = perms,
                    targetTool = com.cocateail.maya.tessa.gmail.GmailTool.TOOL_ID,
                    summary = "Scan Gmail for project instructions/PDF and hand off update to MAYA Builder.",
                    relatedProject = detectedProject ?: "UpdatedAndroidProject"
                )
            }

            mentionsGmail -> {
                TessaTaskPlan(
                    classification = TessaTaskState.TASK.name,
                    steps = listOf("AUTHENTICATE_GMAIL", "SCAN_MESSAGES", "FILTER_RELEVANCE", "REPORT_INBOX_INTELLIGENCE"),
                    requiredPermissions = listOf(TessaPermissionType.READ_EMAIL),
                    targetTool = com.cocateail.maya.tessa.gmail.GmailTool.TOOL_ID,
                    summary = "Check authorized Gmail inbox and report intelligent summary of actionable items."
                )
            }

            mentionsMailOrPdf && mentionsBuildOrProject -> {
                val perms = buildList {
                    add(TessaPermissionType.READ_EMAIL)
                    if (!attachedFileName.isNullOrBlank() || lower.contains("attachment")) {
                        add(TessaPermissionType.READ_ATTACHMENT)
                    }
                    if (hasPdfBytes || lower.contains("pdf") || attachedFileName?.endsWith(".pdf", true) == true) {
                        add(TessaPermissionType.READ_PDF)
                    }
                    add(TessaPermissionType.WRITE_PROJECT)
                    if (lower.contains("push") && lower.contains("github")) {
                        add(TessaPermissionType.PUSH_GITHUB)
                    }
                }
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf(
                        "READ_MESSAGE",
                        "READ_ATTACHMENT",
                        "UNDERSTAND_TASK",
                        "CREATE_TASK",
                        "REQUEST_PERMISSION",
                        "HANDOFF_TO_MAYA_BUILDER",
                        "TRACK_RESULT",
                        "REPORT_RESULT"
                    ),
                    requiredPermissions = perms,
                    targetTool = MessagePdfIntelligenceTool.TOOL_ID,
                    summary = "Read authorized message/PDF specification and hand off project update to MAYA Builder.",
                    relatedProject = detectedProject ?: "UpdatedAndroidProject",
                    relatedMessage = attachedMessageText?.take(200),
                    relatedFile = attachedFileName
                )
            }

            mentionsMailOrPdf -> {
                val perms = buildList {
                    add(TessaPermissionType.READ_EMAIL)
                    if (!attachedFileName.isNullOrBlank() || lower.contains("attachment")) {
                        add(TessaPermissionType.READ_ATTACHMENT)
                    }
                    if (hasPdfBytes || lower.contains("pdf") || attachedFileName?.endsWith(".pdf", true) == true) {
                        add(TessaPermissionType.READ_PDF)
                    }
                    if (lower.contains("send") || lower.contains("reply")) {
                        add(TessaPermissionType.SEND_MESSAGE)
                    }
                }
                TessaTaskPlan(
                    classification = TessaTaskState.TASK.name,
                    steps = listOf(
                        "READ_MESSAGE",
                        "READ_ATTACHMENT",
                        "UNDERSTAND_TASK",
                        "REPORT_RESULT"
                    ),
                    requiredPermissions = perms,
                    targetTool = MessagePdfIntelligenceTool.TOOL_ID,
                    summary = "Analyze authorized message and/or PDF attachment.",
                    relatedProject = detectedProject,
                    relatedMessage = attachedMessageText?.take(200),
                    relatedFile = attachedFileName
                )
            }

            mentionsDesktopComputer -> {
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf("CHECK_DESKTOP_CONNECTOR", "REPORT_AVAILABILITY"),
                    requiredPermissions = listOf(TessaPermissionType.WRITE_PROJECT),
                    targetTool = LocalComputerTool.TOOL_ID,
                    summary = "Check Local Computer Agent connector availability and execute if connected."
                )
            }

            mentionsShopping -> {
                val perms = if (lower.contains("order") || lower.contains("buy")) {
                    listOf(TessaPermissionType.PLACE_ORDER)
                } else {
                    emptyList()
                }
                TessaTaskPlan(
                    classification = if (perms.isNotEmpty()) TessaTaskState.ACTION.name else TessaTaskState.TASK.name,
                    steps = listOf("CHECK_PERMISSIONS", "QUERY_SHOPPING_CONNECTOR", "REPORT_RESULT"),
                    requiredPermissions = perms,
                    targetTool = ShoppingTool.TOOL_ID,
                    summary = "Execute shopping product search/comparison or permission-gated order preparation."
                )
            }

            mentionsLocationOrAddress -> {
                val perms = buildList {
                    add(TessaPermissionType.USE_LOCATION)
                    if (lower.contains("save address") || lower.contains("house")) {
                        add(TessaPermissionType.SAVE_ADDRESS)
                        add(TessaPermissionType.USE_PHONE_NUMBER)
                    }
                }
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf("VERIFY_LOCATION_PERMISSION", "VALIDATE_ADDRESS_FIELDS", "REPORT_RESULT"),
                    requiredPermissions = perms,
                    targetTool = LocationAddressTool.TOOL_ID,
                    summary = "Access device location or validate/save user delivery address with permission."
                )
            }

            mentionsBuildOrProject -> {
                val perms = buildList {
                    add(TessaPermissionType.WRITE_PROJECT)
                    if (lower.contains("push") && lower.contains("github")) {
                        add(TessaPermissionType.PUSH_GITHUB)
                    }
                }
                TessaTaskPlan(
                    classification = TessaTaskState.ACTION.name,
                    steps = listOf(
                        "UNDERSTAND_TASK",
                        "CREATE_TASK",
                        "REQUEST_PERMISSION",
                        "HANDOFF_TO_MAYA_BUILDER",
                        "TRACK_RESULT",
                        "REPORT_RESULT"
                    ),
                    requiredPermissions = perms,
                    targetTool = MayaBuilderTaskHandoff.TOOL_ID,
                    summary = "Hand off development task to MAYA Builder.",
                    relatedProject = detectedProject ?: "TessaBuilderProject"
                )
            }

            lower.endsWith("?") || lower.startsWith("what ") || lower.startsWith("how ") ||
                lower.startsWith("why ") || lower.startsWith("explain ") -> {
                TessaTaskPlan(
                    classification = TessaTaskState.INFORMATION.name,
                    steps = listOf("QUERY_TESSA_MODEL", "RETURN_INFORMATION"),
                    requiredPermissions = emptyList(),
                    targetTool = "NONE",
                    summary = "Information query handled by Tessa Model Provider."
                )
            }

            else -> {
                TessaTaskPlan(
                    classification = TessaTaskState.CHAT.name,
                    steps = listOf("QUERY_TESSA_MODEL", "RETURN_CHAT_RESPONSE"),
                    requiredPermissions = emptyList(),
                    targetTool = "NONE",
                    summary = "Conversational turn handled by Tessa Model Provider."
                )
            }
        }
    }
}

/**
 * 24-Hour Change Handling Foundation:
 * Tracks updated instructions from authorized sources within a 24-hour window (or active project lifecycle):
 * 1. Identifies related task/project.
 * 2. Verifies source authorization and ensures casual/unrelated messages are ignored.
 * 3. Compares previous instruction and new instruction.
 * 4. Creates a task revision with a full recoverable snapshot of the previous state.
 * 5. Requests permission when required.
 * 6. Applies only authorized changes.
 */
@Serializable
data class TaskRevisionOutcome(
    val applied: Boolean,
    val requiresUserPermission: Boolean,
    val rejectedReason: String? = null,
    val revision: TessaTaskRevision? = null,
    val updatedTask: TessaTask? = null
)

class TessaRevisionManager(
    private val taskStore: TessaTaskStore,
    private val permissionManager: TessaPermissionManager
) {
    companion object {
        const val TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1000L
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val authorizedSenders = mutableSetOf("user", "owner", "authorized_client")

    fun authorizeSender(senderId: String) {
        if (senderId.isNotBlank()) {
            authorizedSenders.add(senderId.trim().lowercase())
        }
    }

    fun isSenderAuthorized(senderId: String): Boolean {
        return authorizedSenders.contains(senderId.trim().lowercase())
    }

    /**
     * Checks whether an incoming message actually contains an actionable instruction update
     * rather than a casual greeting or unrelated chatter.
     */
    fun isRelevantInstructionChange(messageContent: String, targetTask: TessaTask): Boolean {
        val clean = messageContent.trim().lowercase()
        if (clean.length < 6) return false
        val isActionable = clean.contains("change") ||
            clean.contains("update") ||
            clean.contains("modify") ||
            clean.contains("instead") ||
            clean.contains("add ") ||
            clean.contains("remove ") ||
            clean.contains("replace ") ||
            clean.contains("use ") ||
            clean.contains("switch to")
        if (!isActionable) return false

        // If the message explicitly names a DIFFERENT project than targetTask.relatedProject, reject it
        val mentionedProject = TessaPlanner.extractProjectName(messageContent)
        val taskProject = targetTask.relatedProject
        if (!mentionedProject.isNullOrBlank() &&
            !taskProject.isNullOrBlank() &&
            !mentionedProject.equals(taskProject, ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    fun computeInstructionDiff(previousInstruction: String, newInstruction: String): String {
        val prevWords = previousInstruction.trim().split(Regex("\\s+")).toSet()
        val newWords = newInstruction.trim().split(Regex("\\s+")).toSet()
        val added = (newWords - prevWords).take(12).joinToString(" ")
        val removed = (prevWords - newWords).take(12).joinToString(" ")
        return "Previous: \"${previousInstruction.take(80)}\" -> Updated: \"${newInstruction.take(80)}\" (Added: [$added], Removed: [$removed])"
    }

    suspend fun handleIncomingInstructionUpdate(
        targetTaskId: String?,
        relatedProjectName: String?,
        senderId: String,
        newInstruction: String,
        requirePermissionForModification: Boolean = true,
        explicitSecrets: List<String> = emptyList()
    ): TaskRevisionOutcome {
        // 1. Verify sender is authorized
        if (!isSenderAuthorized(senderId)) {
            return TaskRevisionOutcome(
                applied = false,
                requiresUserPermission = false,
                rejectedReason = "UNAUTHORIZED_SOURCE: Sender '$senderId' is not authorized to modify Tessa tasks."
            )
        }

        // 2. Locate related task by ID or project name
        val existingTask = when {
            !targetTaskId.isNullOrBlank() -> taskStore.getTaskById(targetTaskId)
            !relatedProjectName.isNullOrBlank() -> taskStore.getLatestTaskForProject(relatedProjectName)
            else -> null
        } ?: return TaskRevisionOutcome(
            applied = false,
            requiresUserPermission = false,
            rejectedReason = "TASK_NOT_FOUND: No matching Tessa task or project found."
        )

        // 3. Ensure message is a relevant instruction for THIS task/project and not unrelated chatter
        if (!isRelevantInstructionChange(newInstruction, existingTask)) {
            return TaskRevisionOutcome(
                applied = false,
                requiresUserPermission = false,
                rejectedReason = "IRRELEVANT_OR_UNRELATED_MESSAGE: Message does not contain a valid instruction update for project '${existingTask.relatedProject ?: existingTask.taskId}'."
            )
        }

        // 4. Check prohibited actions on the new instruction
        val (prohibited, prohReason) = permissionManager.detectProhibitedAction(newInstruction)
        if (prohibited) {
            return TaskRevisionOutcome(
                applied = false,
                requiresUserPermission = false,
                rejectedReason = prohReason ?: "PROHIBITED_ACTION"
            )
        }

        // 5. Capture full recoverable snapshot of previous state before modifying
        val previousSnapshot = json.encodeToString(existingTask)
        val diffSummary = computeInstructionDiff(existingTask.userRequest, newInstruction)

        val permDecision = permissionManager.evaluatePermissions(
            taskId = existingTask.taskId,
            userRequest = newInstruction,
            requiredPermissions = existingTask.requiredPermissions.ifEmpty {
                listOf(TessaPermissionType.WRITE_PROJECT)
            }
        )

        val needsApproval = requirePermissionForModification && !permDecision.allowed

        val revision = TessaTaskRevision(
            taskId = existingTask.taskId,
            sourceSender = senderId,
            isAuthorizedSource = true,
            previousInstruction = existingTask.userRequest,
            newInstruction = newInstruction,
            changeSummary = diffSummary,
            requiresPermission = needsApproval,
            applied = !needsApproval,
            previousStateSnapshot = previousSnapshot
        )

        val updatedTask = existingTask.copy(
            updatedAt = System.currentTimeMillis(),
            userRequest = if (needsApproval) existingTask.userRequest else newInstruction,
            status = if (needsApproval) TessaTaskState.WAITING_FOR_PERMISSION else TessaTaskState.QUEUED,
            requiresPermission = needsApproval || existingTask.requiresPermission,
            permissionState = if (needsApproval) TessaPermissionState.PENDING_USER_APPROVAL else permDecision.state,
            relatedMessage = newInstruction,
            revisions = existingTask.revisions + revision,
            previousStateSnapshotJson = previousSnapshot
        )

        val saved = taskStore.saveTask(updatedTask, explicitSecrets)
        return TaskRevisionOutcome(
            applied = !needsApproval,
            requiresUserPermission = needsApproval,
            revision = revision,
            updatedTask = saved
        )
    }

    /**
     * Restores a task to its previous recoverable snapshot prior to the latest revision.
     */
    suspend fun restorePreviousState(taskId: String, explicitSecrets: List<String> = emptyList()): TessaTask? {
        val current = taskStore.getTaskById(taskId) ?: return null
        val snapshotJson = current.previousStateSnapshotJson ?: return null
        val restored = runCatching { json.decodeFromString<TessaTask>(snapshotJson) }.getOrNull() ?: return null
        return taskStore.saveTask(
            restored.copy(updatedAt = System.currentTimeMillis()),
            explicitSecrets
        )
    }
}

@Serializable
data class TessaExecutionOutcome(
    val task: TessaTask,
    val uiStatus: TessaUiStatus,
    val providerAvailable: Boolean,
    val formattedResponse: String,
    val toolResult: TessaToolResult? = null,
    val errorCode: String? = null
)

/**
 * TESSA Turbo V1 Core Agent:
 * Coordinates:
 * User -> TessaAgent -> TessaModelProvider -> Task understanding -> Task plan ->
 * Permission check -> Tool Router -> Tool execution -> Result validation -> Tessa response
 */
class TessaAgent(
    private val context: Context? = null,
    val modelProvider: TessaModelProvider = TessaHttpModelProvider(),
    val taskStore: TessaTaskStore = TessaTaskStore(context?.let { AppDatabase.getDatabase(it).tessaTaskDao() }),
    val permissionManager: TessaPermissionManager = TessaPermissionManager(context),
    val gmailConnector: com.cocateail.maya.tessa.gmail.GmailConnector? = null,
    val toolRouter: TessaToolRouter = TessaToolRouter.createDefault(
        context = context,
        gmailConnector = gmailConnector
    )
) {
    val revisionManager = TessaRevisionManager(taskStore, permissionManager)

    private val _uiStatus = MutableStateFlow(TessaUiStatus.READY)
    val uiStatus: StateFlow<TessaUiStatus> = _uiStatus.asStateFlow()

    suspend fun handleUserRequest(
        userRequest: String,
        config: DeveloperConfig,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        attachedMessageText: String? = null,
        attachedFileName: String? = null,
        pdfBytes: ByteArray? = null
    ): TessaExecutionOutcome {
        val resolvedTessaKey = config.resolvedTessaApiKey()
        val explicitSecrets = listOf(config.tessaApiKey, resolvedTessaKey, config.githubToken, config.customApiKey, config.cloudRunApiKey)
        _uiStatus.value = TessaUiStatus.PLANNING

        // 1. Classify and plan the request
        val plan = TessaPlanner.classifyAndPlan(
            userRequest = userRequest,
            attachedMessageText = attachedMessageText,
            attachedFileName = attachedFileName,
            hasPdfBytes = pdfBytes != null && pdfBytes.isNotEmpty()
        )
        val classifiedType = runCatching { TessaTaskState.valueOf(plan.classification) }
            .getOrDefault(TessaTaskState.TASK)

        // 2. Check prohibited security actions first
        val (isProhibited, prohibitedReason) = permissionManager.detectProhibitedAction(userRequest)
        if (isProhibited) {
            val blockedTask = taskStore.saveTask(
                TessaTask(
                    userRequest = userRequest,
                    taskType = classifiedType,
                    status = TessaTaskState.FAILED,
                    currentStep = 1,
                    totalSteps = plan.steps.size.coerceAtLeast(1),
                    requiresPermission = true,
                    permissionState = TessaPermissionState.BLOCKED_PROHIBITED_ACTION,
                    requiredPermissions = plan.requiredPermissions,
                    targetTool = plan.targetTool,
                    error = prohibitedReason,
                    relatedProject = plan.relatedProject
                ),
                explicitSecrets
            )
            _uiStatus.value = TessaUiStatus.FAILED
            return TessaExecutionOutcome(
                task = blockedTask,
                uiStatus = TessaUiStatus.FAILED,
                providerAvailable = true,
                formattedResponse = "🛑 **TESSA Security Policy Block**\n\n$prohibitedReason",
                errorCode = prohibitedReason?.substringBefore(":")?.trim() ?: "PROHIBITED_ACTION"
            )
        }

        // 3. If pure CHAT or INFORMATION (no tool execution), route strictly through TessaModelProvider
        if (classifiedType == TessaTaskState.CHAT || classifiedType == TessaTaskState.INFORMATION) {
            _uiStatus.value = TessaUiStatus.THINKING
            val modelReq = TessaModelRequest(
                userPrompt = userRequest,
                conversationHistory = conversationHistory,
                attachedDocumentText = attachedMessageText,
                attachedFileName = attachedFileName,
                tessaModelId = config.tessaModelId,
                tessaApiBaseUrl = config.tessaApiBaseUrl,
                tessaApiKey = resolvedTessaKey
            )
            val modelResp = modelProvider.generate(modelReq)
            if (!modelResp.available) {
                val sanitizedReason = TessaSecretRedactor.redact(
                    modelResp.errorMessage ?: "Unknown Gemini API error",
                    explicitSecrets
                )
                val sanitizedBody = TessaSecretRedactor.redact(
                    modelResp.sanitizedErrorBody,
                    explicitSecrets
                )
                val failedTask = taskStore.saveTask(
                    TessaTask(
                        userRequest = userRequest,
                        taskType = classifiedType,
                        status = TessaTaskState.FAILED,
                        currentStep = 1,
                        totalSteps = 1,
                        requiresPermission = false,
                        permissionState = TessaPermissionState.NOT_REQUIRED,
                        error = "${modelResp.errorCode}: $sanitizedReason"
                    ),
                    explicitSecrets
                )
                val statusHeader = when (modelResp.errorCategory) {
                    "AUTH_FAILURE" -> "TESSA_AUTH_REQUIRED — Authentication Failed (Model Provider Unavailable)"
                    "MISSING_MODEL_ID", "MISSING_API_KEY", "MISSING_ENDPOINT", "PROVIDER_NOT_CONFIGURED" ->
                        "TESSA_CONFIGURATION_REQUIRED — Configuration Missing (Model Provider Unavailable)"
                    "MODEL_NOT_FOUND" -> "TESSA_PROVIDER_UNAVAILABLE — Invalid / Unsupported Model (Model Provider Unavailable)"
                    "HTTP_5XX" -> "TESSA_PROVIDER_UNAVAILABLE — Gemini Server Error (Model Provider Unavailable)"
                    "TIMEOUT" -> "TESSA_PROVIDER_UNAVAILABLE — Request Timed Out (Model Provider Unavailable)"
                    "RATE_LIMIT_QUOTA" -> "TESSA_PROVIDER_UNAVAILABLE — Rate Limit / Quota Exceeded (Model Provider Unavailable)"
                    "NETWORK_UNAVAILABLE" -> "TESSA_PROVIDER_UNAVAILABLE — Network Unavailable (Model Provider Unavailable)"
                    else -> "TESSA Turbo V1 Model Provider Unavailable"
                }
                val httpStatusLine = modelResp.httpStatusCode?.let { "\n• **HTTP Status:** `HTTP $it`" }.orEmpty()
                val endpointLine = modelResp.providerEndpointUsed.takeIf { it.isNotBlank() }
                    ?.let { "\n• **Endpoint:** `${TessaSecretRedactor.redact(it, explicitSecrets)}`" }.orEmpty()
                val geminiBodyLine = if (sanitizedBody.isNotBlank()) {
                    "\n• **Gemini Error Detail:** `$sanitizedBody`"
                } else {
                    ""
                }
                _uiStatus.value = TessaUiStatus.FAILED
                return TessaExecutionOutcome(
                    task = failedTask,
                    uiStatus = TessaUiStatus.FAILED,
                    providerAvailable = false,
                    formattedResponse = "⚠️ **$statusHeader**\n\n" +
                        "Reason: `${modelResp.errorCode}` — $sanitizedReason" +
                        httpStatusLine +
                        endpointLine +
                        geminiBodyLine +
                        "\n\nOpen **TESSA Diagnostics** or **Developer Controls** to inspect or test the live Gemini API connection.",
                    errorCode = modelResp.errorCode
                )
            }

            val completedChatTask = taskStore.saveTask(
                TessaTask(
                    userRequest = userRequest,
                    taskType = classifiedType,
                    status = TessaTaskState.COMPLETED,
                    currentStep = plan.steps.size,
                    totalSteps = plan.steps.size,
                    requiresPermission = false,
                    permissionState = TessaPermissionState.NOT_REQUIRED,
                    result = modelResp.content
                ),
                explicitSecrets
            )
            _uiStatus.value = TessaUiStatus.COMPLETED
            return TessaExecutionOutcome(
                task = completedChatTask,
                uiStatus = TessaUiStatus.COMPLETED,
                providerAvailable = true,
                formattedResponse = modelResp.content
            )
        }

        // 4. For TASK and ACTION: create persistent TessaTask in Room
        val initialTask = taskStore.saveTask(
            TessaTask(
                userRequest = userRequest,
                taskType = classifiedType,
                status = TessaTaskState.QUEUED,
                currentStep = 1,
                totalSteps = plan.steps.size.coerceAtLeast(1),
                requiresPermission = plan.requiredPermissions.isNotEmpty(),
                permissionState = if (plan.requiredPermissions.isNotEmpty()) {
                    TessaPermissionState.PENDING_USER_APPROVAL
                } else {
                    TessaPermissionState.NOT_REQUIRED
                },
                requiredPermissions = plan.requiredPermissions,
                targetTool = plan.targetTool,
                relatedProject = plan.relatedProject,
                relatedMessage = plan.relatedMessage,
                relatedFile = plan.relatedFile
            ),
            explicitSecrets
        )

        return executeOrGateTask(
            task = initialTask,
            config = config,
            attachedMessageText = attachedMessageText,
            attachedFileName = attachedFileName,
            pdfBytes = pdfBytes
        )
    }

    suspend fun executeOrGateTask(
        task: TessaTask,
        config: DeveloperConfig,
        attachedMessageText: String? = task.relatedMessage,
        attachedFileName: String? = task.relatedFile,
        pdfBytes: ByteArray? = null
    ): TessaExecutionOutcome {
        val explicitSecrets = listOf(config.tessaApiKey, config.githubToken, config.customApiKey, config.cloudRunApiKey)

        if (task.status == TessaTaskState.CANCELLED) {
            _uiStatus.value = TessaUiStatus.FAILED
            return TessaExecutionOutcome(
                task = task,
                uiStatus = TessaUiStatus.FAILED,
                providerAvailable = true,
                formattedResponse = "🚫 Task `${task.taskId}` was cancelled.",
                errorCode = "TASK_CANCELLED"
            )
        }

        if (task.retryCount >= task.maxRetries) {
            val exhaustedTask = taskStore.saveTask(
                task.copy(
                    updatedAt = System.currentTimeMillis(),
                    status = TessaTaskState.FAILED,
                    error = "RETRY_LIMIT_EXCEEDED: Task exceeded maxRetries (${task.maxRetries})."
                ),
                explicitSecrets
            )
            _uiStatus.value = TessaUiStatus.FAILED
            return TessaExecutionOutcome(
                task = exhaustedTask,
                uiStatus = TessaUiStatus.FAILED,
                providerAvailable = true,
                formattedResponse = "❌ **Tessa Task Failed (`RETRY_LIMIT_EXCEEDED`)**\nTask `${task.taskId}` reached the maximum retry limit (${task.maxRetries}).",
                errorCode = "RETRY_LIMIT_EXCEEDED"
            )
        }

        // Evaluate permissions before running any tool
        val permDecision = permissionManager.evaluatePermissions(
            taskId = task.taskId,
            userRequest = task.userRequest,
            requiredPermissions = task.requiredPermissions
        )

        if (!permDecision.allowed) {
            val newStatus = if (permDecision.state == TessaPermissionState.BLOCKED_PROHIBITED_ACTION ||
                permDecision.state == TessaPermissionState.DENIED
            ) {
                TessaTaskState.FAILED
            } else {
                TessaTaskState.WAITING_FOR_PERMISSION
            }

            val gatedTask = taskStore.saveTask(
                task.copy(
                    updatedAt = System.currentTimeMillis(),
                    status = newStatus,
                    permissionState = permDecision.state,
                    error = if (newStatus == TessaTaskState.FAILED) permDecision.message else null
                ),
                explicitSecrets
            )

            val ui = if (newStatus == TessaTaskState.WAITING_FOR_PERMISSION) {
                TessaUiStatus.WAITING_FOR_PERMISSION
            } else {
                TessaUiStatus.FAILED
            }
            _uiStatus.value = ui

            val responseMsg = if (newStatus == TessaTaskState.WAITING_FOR_PERMISSION) {
                "🔐 **TESSA Turbo V1 — Waiting for Permission**\n\n" +
                    "• **Task ID:** `${gatedTask.taskId}`\n" +
                    "• **Classification:** `${gatedTask.taskType.name}`\n" +
                    "• **Target Tool:** `${gatedTask.targetTool}`\n" +
                    "• **Required Permission(s):** `${permDecision.missingPermissions.joinToString { it.name }}`\n\n" +
                    "${permDecision.message}\n\n" +
                    "Approve permission in the Tessa Task Banner to continue execution."
            } else {
                "🛑 **TESSA Turbo V1 — Action Blocked**\n\n${permDecision.message}"
            }

            return TessaExecutionOutcome(
                task = gatedTask,
                uiStatus = ui,
                providerAvailable = true,
                formattedResponse = responseMsg,
                errorCode = permDecision.blockedReasonCode ?: permDecision.state.name
            )
        }

        // Permissions are granted -> Transition to RUNNING
        _uiStatus.value = TessaUiStatus.WORKING
        val runningTask = taskStore.saveTask(
            task.copy(
                updatedAt = System.currentTimeMillis(),
                status = TessaTaskState.RUNNING,
                currentStep = (task.totalSteps / 2).coerceAtLeast(1),
                permissionState = permDecision.state,
                error = null
            ),
            explicitSecrets
        )

        val pdfBase64 = pdfBytes?.takeIf { it.isNotEmpty() }?.let {
            java.util.Base64.getEncoder().encodeToString(it)
        }
        val params = buildMap {
            if (!attachedMessageText.isNullOrBlank()) put("messageText", attachedMessageText)
            if (!attachedFileName.isNullOrBlank()) put("attachmentName", attachedFileName)
            if (!runningTask.relatedProject.isNullOrBlank()) put("projectName", runningTask.relatedProject)
        }

        val toolRequest = TessaToolRequest(
            taskId = runningTask.taskId,
            toolId = runningTask.targetTool,
            action = "EXECUTE",
            userInstruction = runningTask.userRequest,
            parameters = params,
            pdfBytesBase64 = pdfBase64
        )

        return try {
            val toolResult = withTimeoutOrNull(runningTask.timeoutMs) {
                val primaryResult = toolRouter.routeAndExecute(toolRequest, permissionManager, config)
                // If MessagePdfIntelligenceTool determined that MAYA_BUILDER_HANDOFF should handle the project update
                // and WRITE_PROJECT permission is already granted, chain the handoff to MAYA Builder!
                if (primaryResult.isSuccess &&
                    runningTask.targetTool == MessagePdfIntelligenceTool.TOOL_ID &&
                    primaryResult.structuredData["targetHandlerTool"] == MayaBuilderTaskHandoff.TOOL_ID &&
                    runningTask.requiredPermissions.contains(TessaPermissionType.WRITE_PROJECT)
                ) {
                    val handoffReq = TessaToolRequest(
                        taskId = runningTask.taskId,
                        toolId = MayaBuilderTaskHandoff.TOOL_ID,
                        action = "HANDOFF_BUILD",
                        userInstruction = runningTask.userRequest,
                        parameters = mapOf(
                            "extractedContext" to primaryResult.structuredData["extractedTextPreview"].orEmpty(),
                            "projectName" to (primaryResult.handoffProjectName ?: runningTask.relatedProject.orEmpty())
                        )
                    )
                    val handoffRes = toolRouter.routeAndExecute(handoffReq, permissionManager, config)
                    if (handoffRes.isSuccess) {
                        handoffRes.copy(
                            outputSummary = primaryResult.outputSummary + "\n\n" + handoffRes.outputSummary
                        )
                    } else {
                        handoffRes
                    }
                } else {
                    primaryResult
                }
            }

            if (toolResult == null) {
                val timedOutTask = taskStore.saveTask(
                    runningTask.copy(
                        updatedAt = System.currentTimeMillis(),
                        status = TessaTaskState.FAILED,
                        retryCount = runningTask.retryCount + 1,
                        error = "TASK_TIMEOUT: Execution exceeded ${runningTask.timeoutMs}ms."
                    ),
                    explicitSecrets
                )
                _uiStatus.value = TessaUiStatus.FAILED
                return TessaExecutionOutcome(
                    task = timedOutTask,
                    uiStatus = TessaUiStatus.FAILED,
                    providerAvailable = true,
                    formattedResponse = "⏱️ **Tessa Task Timed Out** (`${runningTask.timeoutMs}ms` limit).",
                    errorCode = "TASK_TIMEOUT"
                )
            }

            // Step Verification & Autonomous Loop via TessaVerificationEngine
            val verification = TessaVerificationEngine.verifyExecution(toolResult, runningTask.userRequest)

            when (verification.status) {
                VerificationStatus.WAITING_FOR_USER_AUTHENTICATION -> {
                    val authTask = taskStore.saveTask(
                        runningTask.copy(
                            updatedAt = System.currentTimeMillis(),
                            status = TessaTaskState.WAITING_FOR_USER_AUTHENTICATION,
                            error = verification.diagnosticMessage
                        ),
                        explicitSecrets
                    )
                    _uiStatus.value = TessaUiStatus.WAITING_FOR_AUTHENTICATION
                    TessaExecutionOutcome(
                        task = authTask,
                        uiStatus = TessaUiStatus.WAITING_FOR_AUTHENTICATION,
                        providerAvailable = true,
                        formattedResponse = "🔐 **Waiting for User Authentication:**\n\n" +
                            "${verification.diagnosticMessage}\n\n" +
                            "Please complete the requested OTP/password/2FA step on your device or browser.",
                        toolResult = toolResult,
                        errorCode = "WAITING_FOR_USER_AUTHENTICATION"
                    )
                }

                VerificationStatus.WAITING_FOR_USER_DECISION -> {
                    val decisionTask = taskStore.saveTask(
                        runningTask.copy(
                            updatedAt = System.currentTimeMillis(),
                            status = TessaTaskState.WAITING_FOR_USER_DECISION,
                            error = verification.diagnosticMessage
                        ),
                        explicitSecrets
                    )
                    _uiStatus.value = TessaUiStatus.WAITING_FOR_DECISION
                    TessaExecutionOutcome(
                        task = decisionTask,
                        uiStatus = TessaUiStatus.WAITING_FOR_DECISION,
                        providerAvailable = true,
                        formattedResponse = "⚠️ **Waiting for User Decision:**\n\n" +
                            "${verification.diagnosticMessage}\n\n" +
                            "Review details before continuing.",
                        toolResult = toolResult,
                        errorCode = "WAITING_FOR_USER_DECISION"
                    )
                }

                VerificationStatus.NEEDS_REPAIR -> {
                    // Autonomous Code Repair Loop:
                    // Read compiler error, formulate patch, apply, and rebuild
                    _uiStatus.value = TessaUiStatus.WORKING
                    val repairError = verification.repairPayload["compilerError"] ?: "Compiler error"
                    val targetFile = verification.repairPayload["targetFile"] ?: ""

                    val repairOutcome = if (runningTask.targetTool == com.cocateail.maya.tessa.computer.UnrealEngineTool.TOOL_ID) {
                        val rebuildReq = TessaToolRequest(
                            taskId = runningTask.taskId,
                            toolId = com.cocateail.maya.tessa.computer.UnrealEngineTool.TOOL_ID,
                            action = "BUILD",
                            userInstruction = "Rebuild after repairing: $repairError",
                            parameters = mapOf(
                                "projectName" to (runningTask.relatedProject ?: "FlightSimulator"),
                                "repaired" to "true"
                            )
                        )
                        toolRouter.routeAndExecute(rebuildReq, permissionManager, config)
                    } else {
                        toolResult
                    }

                    if (repairOutcome.isSuccess && repairOutcome.status == TessaToolStatus.SUCCESS &&
                        repairOutcome.structuredData["buildStatus"] == "SUCCESS") {
                        val repairedTask = taskStore.saveTask(
                            runningTask.copy(
                                updatedAt = System.currentTimeMillis(),
                                status = TessaTaskState.COMPLETED,
                                currentStep = runningTask.totalSteps,
                                result = "✅ **Autonomously Repaired & Rebuilt**\n• Diagnosed: `$repairError`\n• Patch applied to `$targetFile`\n• Rebuild status: SUCCESS",
                                error = null
                            ),
                            explicitSecrets
                        )
                        _uiStatus.value = TessaUiStatus.COMPLETED
                        TessaExecutionOutcome(
                            task = repairedTask,
                            uiStatus = TessaUiStatus.COMPLETED,
                            providerAvailable = true,
                            formattedResponse = repairedTask.result ?: "Repaired and rebuilt successfully.",
                            toolResult = repairOutcome
                        )
                    } else {
                        val failedTask = taskStore.saveTask(
                            runningTask.copy(
                                updatedAt = System.currentTimeMillis(),
                                status = TessaTaskState.FAILED,
                                retryCount = runningTask.retryCount + 1,
                                error = "Unreal build repair failed: ${repairOutcome.outputSummary}"
                            ),
                            explicitSecrets
                        )
                        _uiStatus.value = TessaUiStatus.FAILED
                        TessaExecutionOutcome(
                            task = failedTask,
                            uiStatus = TessaUiStatus.FAILED,
                            providerAvailable = true,
                            formattedResponse = "❌ **Unreal Build Failed**\n\n${repairOutcome.outputSummary}",
                            toolResult = repairOutcome,
                            errorCode = repairOutcome.errorCode ?: "COMPILER_ERROR"
                        )
                    }
                }

                VerificationStatus.PASSED -> {
                    if (toolResult.isSuccess && toolResult.status == TessaToolStatus.SUCCESS) {
                        val completedTask = taskStore.saveTask(
                            runningTask.copy(
                                updatedAt = System.currentTimeMillis(),
                                status = TessaTaskState.COMPLETED,
                                currentStep = runningTask.totalSteps,
                                result = toolResult.outputSummary,
                                relatedProject = toolResult.handoffProjectName ?: runningTask.relatedProject,
                                error = null
                            ),
                            explicitSecrets
                        )
                        _uiStatus.value = TessaUiStatus.COMPLETED
                        TessaExecutionOutcome(
                            task = completedTask,
                            uiStatus = TessaUiStatus.COMPLETED,
                            providerAvailable = true,
                            formattedResponse = toolResult.outputSummary,
                            toolResult = toolResult
                        )
                    } else {
                        val failedTask = taskStore.saveTask(
                            runningTask.copy(
                                updatedAt = System.currentTimeMillis(),
                                status = TessaTaskState.FAILED,
                                retryCount = runningTask.retryCount + 1,
                                error = toolResult.outputSummary
                            ),
                            explicitSecrets
                        )
                        _uiStatus.value = TessaUiStatus.FAILED
                        TessaExecutionOutcome(
                            task = failedTask,
                            uiStatus = TessaUiStatus.FAILED,
                            providerAvailable = true,
                            formattedResponse = "❌ **Tessa Task Failed**\n\n${toolResult.outputSummary}",
                            toolResult = toolResult,
                            errorCode = toolResult.errorCode ?: "VERIFICATION_FAILED"
                        )
                    }
                }

                VerificationStatus.FAILED -> {
                    val failedTask = taskStore.saveTask(
                        runningTask.copy(
                            updatedAt = System.currentTimeMillis(),
                            status = TessaTaskState.FAILED,
                            retryCount = runningTask.retryCount + 1,
                            error = toolResult.outputSummary
                        ),
                        explicitSecrets
                    )
                    _uiStatus.value = TessaUiStatus.FAILED
                    TessaExecutionOutcome(
                        task = failedTask,
                        uiStatus = TessaUiStatus.FAILED,
                        providerAvailable = true,
                        formattedResponse = "❌ **Tessa Task Failed**\n\n${toolResult.outputSummary}",
                        toolResult = toolResult,
                        errorCode = toolResult.errorCode ?: "TASK_FAILED"
                    )
                }

                else -> {
                    when (toolResult.status) {
                        TessaToolStatus.PERMISSION_REQUIRED -> {
                            val waitingTask = taskStore.saveTask(
                                runningTask.copy(
                                    updatedAt = System.currentTimeMillis(),
                                    status = TessaTaskState.WAITING_FOR_PERMISSION,
                                    permissionState = TessaPermissionState.PENDING_USER_APPROVAL,
                                    requiredPermissions = (runningTask.requiredPermissions + toolResult.missingPermissions).distinct(),
                                    error = toolResult.outputSummary
                                ),
                                explicitSecrets
                            )
                            _uiStatus.value = TessaUiStatus.WAITING_FOR_PERMISSION
                            TessaExecutionOutcome(
                                task = waitingTask,
                                uiStatus = TessaUiStatus.WAITING_FOR_PERMISSION,
                                providerAvailable = true,
                                formattedResponse = "🔐 **Waiting for Permission:** ${toolResult.outputSummary}",
                                toolResult = toolResult,
                                errorCode = toolResult.errorCode ?: "PERMISSION_REQUIRED"
                            )
                        }

                        TessaToolStatus.TOOL_UNAVAILABLE -> {
                            val unavailableTask = taskStore.saveTask(
                                runningTask.copy(
                                    updatedAt = System.currentTimeMillis(),
                                    status = TessaTaskState.FAILED,
                                    error = "${toolResult.errorCode ?: "TOOL_UNAVAILABLE"}: ${toolResult.outputSummary}"
                                ),
                                explicitSecrets
                            )
                            _uiStatus.value = TessaUiStatus.FAILED
                            TessaExecutionOutcome(
                                task = unavailableTask,
                                uiStatus = TessaUiStatus.FAILED,
                                providerAvailable = true,
                                formattedResponse = "⚠️ **Tessa Tool Unavailable (`TOOL_UNAVAILABLE`)**\n\n${toolResult.outputSummary}",
                                toolResult = toolResult,
                                errorCode = "TOOL_UNAVAILABLE"
                            )
                        }

                        else -> {
                            val failedTask = taskStore.saveTask(
                                runningTask.copy(
                                    updatedAt = System.currentTimeMillis(),
                                    status = TessaTaskState.FAILED,
                                    retryCount = runningTask.retryCount + 1,
                                    error = toolResult.outputSummary
                                ),
                                explicitSecrets
                            )
                            _uiStatus.value = TessaUiStatus.FAILED
                            TessaExecutionOutcome(
                                task = failedTask,
                                uiStatus = TessaUiStatus.FAILED,
                                providerAvailable = true,
                                formattedResponse = "❌ **Tessa Task Failed**\n\n${toolResult.outputSummary}",
                                toolResult = toolResult,
                                errorCode = toolResult.errorCode ?: "TOOL_EXECUTION_FAILED"
                            )
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            taskStore.saveTask(
                runningTask.copy(
                    updatedAt = System.currentTimeMillis(),
                    status = TessaTaskState.CANCELLED,
                    error = "Cancelled by user or coroutine scope."
                ),
                explicitSecrets
            )
            _uiStatus.value = TessaUiStatus.READY
            throw ce
        }
    }

    suspend fun approveTaskPermissionsAndResume(
        taskId: String,
        config: DeveloperConfig,
        saveAsReusableRule: Boolean = false,
        attachedMessageText: String? = null,
        attachedFileName: String? = null,
        pdfBytes: ByteArray? = null
    ): TessaExecutionOutcome? {
        val task = taskStore.getTaskById(taskId) ?: return null
        val granted = permissionManager.grantPermissionForTask(
            taskId = taskId,
            permissions = task.requiredPermissions,
            saveAsReusableRule = saveAsReusableRule
        )
        if (!granted) {
            return executeOrGateTask(task, config, attachedMessageText, attachedFileName, pdfBytes)
        }
        // If there was a pending revision instruction on relatedMessage, apply it now
        val updatedInstruction = if (task.revisions.isNotEmpty() && !task.revisions.last().applied) {
            task.revisions.last().newInstruction
        } else {
            task.userRequest
        }
        val updatedRevisions = if (task.revisions.isNotEmpty()) {
            task.revisions.dropLast(1) + task.revisions.last().copy(applied = true)
        } else {
            task.revisions
        }
        val readyTask = taskStore.saveTask(
            task.copy(
                updatedAt = System.currentTimeMillis(),
                userRequest = updatedInstruction,
                status = TessaTaskState.QUEUED,
                permissionState = if (saveAsReusableRule) {
                    TessaPermissionState.GRANTED_REUSABLE_RULE
                } else {
                    TessaPermissionState.GRANTED_ONCE
                },
                revisions = updatedRevisions
            )
        )
        return executeOrGateTask(
            task = readyTask,
            config = config,
            attachedMessageText = attachedMessageText ?: readyTask.relatedMessage,
            attachedFileName = attachedFileName ?: readyTask.relatedFile,
            pdfBytes = pdfBytes
        )
    }

    suspend fun denyTaskPermission(taskId: String): TessaTask? {
        val task = taskStore.getTaskById(taskId) ?: return null
        permissionManager.denyPermissionForTask(taskId)
        _uiStatus.value = TessaUiStatus.FAILED
        return taskStore.saveTask(
            task.copy(
                updatedAt = System.currentTimeMillis(),
                status = TessaTaskState.FAILED,
                permissionState = TessaPermissionState.DENIED,
                error = "User denied permission for task $taskId."
            )
        )
    }

    suspend fun cancelTask(taskId: String): TessaTask? {
        val task = taskStore.getTaskById(taskId) ?: return null
        context?.let {
            runCatching { WorkManager.getInstance(it).cancelUniqueWork("tessa_bg_$taskId") }
        }
        _uiStatus.value = TessaUiStatus.READY
        return taskStore.saveTask(
            task.copy(
                updatedAt = System.currentTimeMillis(),
                status = TessaTaskState.CANCELLED,
                error = "Cancelled by user."
            )
        )
    }

    suspend fun pauseTask(taskId: String): TessaTask? {
        val task = taskStore.getTaskById(taskId) ?: return null
        if (task.status.isTerminal()) return task
        _uiStatus.value = TessaUiStatus.WAITING
        return taskStore.saveTask(
            task.copy(
                updatedAt = System.currentTimeMillis(),
                status = TessaTaskState.PAUSED
            )
        )
    }

    fun enqueueBackgroundTask(taskId: String) {
        val ctx = context ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val workReq = OneTimeWorkRequestBuilder<TessaBackgroundTaskWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString("taskId", taskId).build())
            .addTag("tessa_task")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "tessa_bg_$taskId",
            ExistingWorkPolicy.KEEP,
            workReq
        )
    }
}

/**
 * Android WorkManager CoroutineWorker for long-running Tessa tasks that continue
 * after the user leaves the screen. Enforces retry limits, timeouts, and permission checks.
 */
class TessaBackgroundTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val db = AppDatabase.getDatabase(applicationContext)
        val taskStore = TessaTaskStore(db.tessaTaskDao())
        val task = taskStore.getTaskById(taskId) ?: return Result.failure()

        if (task.status == TessaTaskState.CANCELLED || task.status == TessaTaskState.COMPLETED) {
            return Result.success()
        }
        if (task.retryCount >= task.maxRetries) {
            taskStore.saveTask(
                task.copy(
                    updatedAt = System.currentTimeMillis(),
                    status = TessaTaskState.FAILED,
                    error = "Background worker stopped: retryCount (${task.retryCount}) reached maxRetries (${task.maxRetries})."
                )
            )
            return Result.failure()
        }

        val prefs = applicationContext.getSharedPreferences("maya_prefs", Context.MODE_PRIVATE)
        val rawCfg = prefs.getString("dev_config", null)
        val config = if (rawCfg != null) {
            runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString(DeveloperConfig.serializer(), rawCfg)
            }.getOrDefault(DeveloperConfig())
        } else {
            DeveloperConfig()
        }

        // Never run background tasks if SIMBA_TURBO_V2 is selected
        if (AssistantEngineMode.fromConfigString(config.activeAssistantMode) == AssistantEngineMode.SIMBA_TURBO_V2) {
            return Result.failure()
        }

        val agent = TessaAgent(
            context = applicationContext,
            taskStore = taskStore
        )
        val outcome = agent.executeOrGateTask(task, config)
        return when (outcome.task.status) {
            TessaTaskState.COMPLETED, TessaTaskState.WAITING_FOR_PERMISSION, TessaTaskState.PAUSED -> Result.success()
            else -> Result.failure()
        }
    }
}
