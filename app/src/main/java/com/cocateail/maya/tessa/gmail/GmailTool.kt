package com.cocateail.maya.tessa.gmail

import android.content.Context
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.*
import java.io.File

/**
 * TESSA Turbo V1.1 — Real Gmail Tool
 * Routes official Gmail API operations, applies permission gates, parses attachments,
 * filters email relevance, and hands off project instructions to MAYA Builder.
 */
class GmailTool(
    private val context: Context? = null,
    private val gmailConnector: GmailConnector? = null,
    private val builderHandoff: MayaBuilderTaskHandoff? = null
) : TessaTool {

    companion object {
        const val TOOL_ID = "GMAIL_CONNECTOR"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Official Google Gmail Connector"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.READ_EMAIL)

    override fun isAvailable(): Boolean = gmailConnector != null && gmailConnector.isConnected

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        if (gmailConnector == null || !gmailConnector.isConnected) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.FAILED,
                outputSummary = "Gmail account authorization required. Please connect and authorize your Google account in Developer Controls.",
                errorCode = "WAITING_FOR_USER_AUTHENTICATION"
            )
        }

        val action = request.action.uppercase().trim().ifBlank { "CHECK_INBOX_INTELLIGENCE" }
        val explicitSecrets = listOf(
            config.tessaApiKey,
            config.gmailOAuthAccessToken,
            config.gmailOAuthRefreshToken,
            config.githubToken
        )

        return when (action) {
            "GET_PROFILE" -> executeGetProfile(request, permissionManager, explicitSecrets)
            "LIST_MESSAGES" -> executeListMessages(request, permissionManager, explicitSecrets)
            "READ_MESSAGE" -> executeReadMessage(request, permissionManager, explicitSecrets)
            "READ_ATTACHMENT", "READ_PDF" -> executeReadAttachment(request, permissionManager, explicitSecrets)
            "CREATE_DRAFT" -> executeCreateDraft(request, permissionManager, explicitSecrets)
            "SEND_EMAIL" -> executeSendEmail(request, permissionManager, explicitSecrets)
            "CHECK_INBOX_INTELLIGENCE" -> executeInboxIntelligence(request, permissionManager, config, explicitSecrets)
            "HANDLE_PROJECT_UPDATE" -> executeHandleProjectUpdate(request, permissionManager, config, explicitSecrets)
            else -> {
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Unsupported Gmail action '$action'. Available actions: LIST_MESSAGES, READ_MESSAGE, READ_ATTACHMENT, CREATE_DRAFT, SEND_EMAIL, CHECK_INBOX_INTELLIGENCE, HANDLE_PROJECT_UPDATE.",
                    errorCode = "UNSUPPORTED_ACTION"
                )
            }
        }
    }

    private suspend fun executeGetProfile(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        explicitSecrets: List<String>
    ): TessaToolResult {
        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = listOf(TessaPermissionType.READ_EMAIL)
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

        val profileResult = gmailConnector!!.getProfile()
        return profileResult.fold(
            onSuccess = { profile ->
                permissionManager.recordTraceableSideEffect(
                    userRequest = request.userInstruction,
                    taskId = request.taskId,
                    permissionState = permDecision.state,
                    toolName = toolId,
                    resultSummary = "Read Gmail profile for ${profile.emailAddress} (total messages: ${profile.messagesTotal}).",
                    explicitSecrets = explicitSecrets
                )
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = "📧 **Gmail Account Connected**\n" +
                        "• **Email:** `${profile.emailAddress}`\n" +
                        "• **Total Messages:** ${profile.messagesTotal}\n" +
                        "• **Total Threads:** ${profile.threadsTotal}",
                    structuredData = mapOf(
                        "email" to profile.emailAddress,
                        "messagesTotal" to profile.messagesTotal.toString(),
                        "threadsTotal" to profile.threadsTotal.toString()
                    )
                )
            },
            onFailure = { err ->
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Failed to fetch Gmail profile: ${err.message}",
                    errorCode = (err as? GmailException)?.errorCode?.name ?: "GMAIL_API_ERROR"
                )
            }
        )
    }

    private suspend fun executeListMessages(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        explicitSecrets: List<String>
    ): TessaToolResult {
        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = listOf(TessaPermissionType.READ_EMAIL)
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

        val query = request.parameters["query"]
        val maxResults = request.parameters["maxResults"]?.toIntOrNull() ?: 10
        val listResult = gmailConnector!!.listMessages(query = query, maxResults = maxResults)

        return listResult.fold(
            onSuccess = { messages ->
                permissionManager.recordTraceableSideEffect(
                    userRequest = request.userInstruction,
                    taskId = request.taskId,
                    permissionState = permDecision.state,
                    toolName = toolId,
                    resultSummary = "Listed ${messages.size} Gmail messages (query='$query').",
                    explicitSecrets = explicitSecrets
                )
                val summaryText = if (messages.isEmpty()) {
                    "No messages found matching query: `${query ?: "inbox"}`"
                } else {
                    buildString {
                        appendLine("📬 **Gmail Messages (${messages.size} found):**\n")
                        messages.forEachIndexed { idx, m ->
                            appendLine("${idx + 1}. **${m.subject}**")
                            appendLine("   • From: `${m.sender}`")
                            appendLine("   • ID: `${m.id}` | Thread: `${m.threadId}`")
                            if (m.hasAttachments) appendLine("   • 📎 *Has attachments*")
                            if (m.snippet.isNotBlank()) appendLine("   • *Snippet:* \"${m.snippet.take(100)}...\"")
                            appendLine()
                        }
                    }
                }
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = summaryText.trim(),
                    structuredData = mapOf(
                        "count" to messages.size.toString(),
                        "messageIds" to messages.joinToString(",") { it.id }
                    )
                )
            },
            onFailure = { err ->
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Failed to list Gmail messages: ${err.message}",
                    errorCode = (err as? GmailException)?.errorCode?.name ?: "GMAIL_API_ERROR"
                )
            }
        )
    }

    private suspend fun executeReadMessage(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        explicitSecrets: List<String>
    ): TessaToolResult {
        val msgId = request.parameters["messageId"]
            ?: return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.FAILED,
                outputSummary = "Parameter 'messageId' is required to read a Gmail message.",
                errorCode = "MISSING_MESSAGE_ID"
            )

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = listOf(TessaPermissionType.READ_EMAIL)
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

        val msgResult = gmailConnector!!.getMessage(msgId)
        return msgResult.fold(
            onSuccess = { msg ->
                val relevance = EmailRelevanceFilter.filterAndAnalyze(msg, explicitSecrets)
                val wrappedContent = EmailRelevanceFilter.wrapUntrustedEmailContent(
                    sender = msg.sender,
                    subject = msg.subject,
                    body = msg.body,
                    explicitSecrets = explicitSecrets
                )

                permissionManager.recordTraceableSideEffect(
                    userRequest = request.userInstruction,
                    taskId = request.taskId,
                    permissionState = permDecision.state,
                    toolName = toolId,
                    resultSummary = "Read Gmail message '$msgId' ('${msg.subject}' from '${msg.sender}'). Relevance=${relevance.category}.",
                    explicitSecrets = explicitSecrets
                )

                val summaryText = buildString {
                    appendLine("✉️ **Email Details:**")
                    appendLine("• **Subject:** ${msg.subject}")
                    appendLine("• **From:** `${msg.sender}`")
                    appendLine("• **Category:** `${relevance.category.name}`")
                    if (relevance.extractedProjectName != null) {
                        appendLine("• **Related Project:** `${relevance.extractedProjectName}`")
                    }
                    if (msg.attachments.isNotEmpty()) {
                        appendLine("• **Attachments (${msg.attachments.size}):** ${msg.attachments.joinToString { "${it.filename} (${it.sizeBytes / 1024} KB)" }}")
                    }
                    if (relevance.securityWarnings.isNotEmpty()) {
                        appendLine("• ⚠️ **Security Note:** ${relevance.securityWarnings.first()}")
                    }
                    appendLine("\n**Body Preview:**\n${msg.body.take(500)}")
                }

                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = summaryText.trim(),
                    structuredData = mapOf(
                        "messageId" to msg.id,
                        "threadId" to msg.threadId,
                        "sender" to msg.sender,
                        "subject" to msg.subject,
                        "category" to relevance.category.name,
                        "isActionable" to relevance.isActionable.toString(),
                        "projectName" to (relevance.extractedProjectName ?: ""),
                        "untrustedWrappedBody" to wrappedContent
                    )
                )
            },
            onFailure = { err ->
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Failed to read Gmail message '$msgId': ${err.message}",
                    errorCode = (err as? GmailException)?.errorCode?.name ?: "GMAIL_API_ERROR"
                )
            }
        )
    }

    private suspend fun executeReadAttachment(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        explicitSecrets: List<String>
    ): TessaToolResult {
        val msgId = request.parameters["messageId"] ?: ""
        val attachmentId = request.parameters["attachmentId"] ?: ""
        val filename = request.parameters["filename"] ?: "attachment.pdf"

        val isPdf = filename.endsWith(".pdf", ignoreCase = true)
        val neededPerms = buildList {
            add(TessaPermissionType.READ_EMAIL)
            add(TessaPermissionType.READ_ATTACHMENT)
            if (isPdf) add(TessaPermissionType.READ_PDF)
        }

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = neededPerms
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

        val downloadResult = gmailConnector!!.downloadAttachmentToCache(msgId, attachmentId, filename)
        return downloadResult.fold(
            onSuccess = { file ->
                var parsedText: String? = null
                if (isPdf && file.exists()) {
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes != null) {
                        parsedText = MessagePdfIntelligenceTool.extractTextFromPdfBytes(bytes)
                    }
                }

                permissionManager.recordTraceableSideEffect(
                    userRequest = request.userInstruction,
                    taskId = request.taskId,
                    permissionState = permDecision.state,
                    toolName = toolId,
                    resultSummary = "Downloaded attachment '${file.name}' (${file.length()} bytes, parsedTextLength=${parsedText?.length ?: 0}).",
                    explicitSecrets = explicitSecrets
                )

                val summary = buildString {
                    appendLine("📎 **Attachment Downloaded & Analyzed:**")
                    appendLine("• **Filename:** `${file.name}`")
                    appendLine("• **Size:** ${file.length() / 1024} KB")
                    if (parsedText != null) {
                        appendLine("• **PDF Text Extracted:** ${parsedText.length} characters.")
                        appendLine("\n**Extracted Content Preview:**\n${parsedText.take(400)}")
                    }
                }

                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = summary.trim(),
                    structuredData = mapOf(
                        "filename" to file.name,
                        "sizeBytes" to file.length().toString(),
                        "localPath" to file.absolutePath,
                        "extractedText" to (parsedText ?: "")
                    )
                )
            },
            onFailure = { err ->
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Failed to download attachment '$filename': ${err.message}",
                    errorCode = (err as? GmailException)?.errorCode?.name ?: "ATTACHMENT_NOT_FOUND"
                )
            }
        )
    }

    private suspend fun executeCreateDraft(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        explicitSecrets: List<String>
    ): TessaToolResult {
        val to = request.parameters["to"] ?: ""
        val subject = request.parameters["subject"] ?: "Draft from Tessa"
        val body = request.parameters["body"] ?: request.userInstruction
        val threadId = request.parameters["threadId"]

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = listOf(TessaPermissionType.DRAFT_EMAIL)
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

        val draftResult = gmailConnector!!.createDraft(to = to, subject = subject, body = body, threadId = threadId)
        return draftResult.fold(
            onSuccess = { draft ->
                permissionManager.recordTraceableSideEffect(
                    userRequest = request.userInstruction,
                    taskId = request.taskId,
                    permissionState = permDecision.state,
                    toolName = toolId,
                    resultSummary = "Created Gmail draft to '$to' (subject: '$subject', draftId: '${draft.draftId}').",
                    explicitSecrets = explicitSecrets
                )

                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = "📝 **Draft Created in Gmail**\n" +
                        "• **To:** `${draft.recipient}`\n" +
                        "• **Subject:** ${draft.subject}\n" +
                        "• **Draft ID:** `${draft.draftId}`\n\n" +
                        "Draft is saved in your Gmail account. You can review or send it from Gmail.",
                    structuredData = mapOf(
                        "draftId" to draft.draftId,
                        "recipient" to draft.recipient,
                        "subject" to draft.subject
                    )
                )
            },
            onFailure = { err ->
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Failed to create Gmail draft: ${err.message}",
                    errorCode = (err as? GmailException)?.errorCode?.name ?: "GMAIL_API_ERROR"
                )
            }
        )
    }

    private suspend fun executeSendEmail(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        explicitSecrets: List<String>
    ): TessaToolResult {
        val to = request.parameters["to"] ?: ""
        val subject = request.parameters["subject"] ?: "Message from Tessa"
        val body = request.parameters["body"] ?: request.userInstruction
        val threadId = request.parameters["threadId"]

        // SEND_EMAIL always requires explicit permission unless rule explicitly created
        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = "Send email to '$to' with subject '$subject'",
            requiredPermissions = listOf(TessaPermissionType.SEND_EMAIL)
        )
        if (!permDecision.allowed) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.PERMISSION_REQUIRED,
                outputSummary = "🔐 **Tessa Permission Required to Send Email**\n\n" +
                    "• **Recipient:** `$to`\n" +
                    "• **Subject:** $subject\n" +
                    "• **Message Preview:** \"${body.take(200)}\"\n\n" +
                    "Please approve this action before Tessa sends this email.",
                errorCode = "PERMISSION_REQUIRED",
                missingPermissions = listOf(TessaPermissionType.SEND_EMAIL)
            )
        }

        val sendResult = gmailConnector!!.sendMessage(to = to, subject = subject, body = body, threadId = threadId)
        return sendResult.fold(
            onSuccess = { res ->
                permissionManager.recordTraceableSideEffect(
                    userRequest = request.userInstruction,
                    taskId = request.taskId,
                    permissionState = permDecision.state,
                    toolName = toolId,
                    resultSummary = "Sent email to '$to' (subject: '$subject', messageId: '${res.messageId}').",
                    explicitSecrets = explicitSecrets
                )

                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.SUCCESS,
                    outputSummary = "✉️ **Email Sent Successfully**\n" +
                        "• **Recipient:** `${res.recipient}`\n" +
                        "• **Subject:** ${res.subject}\n" +
                        "• **Message ID:** `${res.messageId}`",
                    structuredData = mapOf(
                        "messageId" to res.messageId,
                        "recipient" to res.recipient,
                        "subject" to res.subject
                    )
                )
            },
            onFailure = { err ->
                TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.FAILED,
                    outputSummary = "Failed to send email: ${err.message}",
                    errorCode = (err as? GmailException)?.errorCode?.name ?: "GMAIL_API_ERROR"
                )
            }
        )
    }

    private suspend fun executeInboxIntelligence(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig,
        explicitSecrets: List<String>
    ): TessaToolResult {
        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = listOf(TessaPermissionType.READ_EMAIL)
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

        val listRes = gmailConnector!!.listMessages(maxResults = 5)
        val summaries = listRes.getOrElse { emptyList() }
        if (summaries.isEmpty()) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.SUCCESS,
                outputSummary = "Checked your Gmail inbox — no new unread or pending action items found.",
                structuredData = mapOf("count" to "0")
            )
        }

        val reports = mutableListOf<Triple<GmailMessageSummary, GmailMessage?, EmailRelevanceReport>>()
        for (summary in summaries.take(3)) {
            val msgRes = gmailConnector.getMessage(summary.id)
            if (msgRes.isSuccess) {
                val fullMsg = msgRes.getOrThrow()
                val report = EmailRelevanceFilter.filterAndAnalyze(fullMsg, explicitSecrets)
                reports.add(Triple(summary, fullMsg, report))
            }
        }

        val actionable = reports.filter { it.third.isActionable }
        val outputText = buildString {
            appendLine("📬 **Tessa Inbox Intelligence Report (${summaries.size} messages scanned):**\n")
            if (actionable.isNotEmpty()) {
                appendLine("⚡ **Actionable Items Requiring Attention:**")
                actionable.forEach { (sum, _, rep) ->
                    appendLine("• **${sum.subject}** (From `${sum.sender}`)")
                    appendLine("  └ Category: `${rep.category.name}` — ${rep.extractedAction}")
                    if (rep.extractedProjectName != null) {
                        appendLine("  └ Target Project: `${rep.extractedProjectName}`")
                    }
                }
                appendLine()
            } else {
                appendLine("✅ All recent messages are informational or casual; no urgent tasks required.\n")
            }
        }

        return TessaToolResult(
            toolId = toolId,
            status = TessaToolStatus.SUCCESS,
            outputSummary = outputText.trim(),
            structuredData = mapOf(
                "scannedCount" to summaries.size.toString(),
                "actionableCount" to actionable.size.toString()
            )
        )
    }

    private suspend fun executeHandleProjectUpdate(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig,
        explicitSecrets: List<String>
    ): TessaToolResult {
        val projectName = request.parameters["projectName"]
            ?: TessaPlanner.extractProjectName(request.userInstruction)
            ?: "UpdatedAndroidProject"

        val wantsGitHub = request.parameters["pushToGitHub"]?.equals("true", true) == true ||
            request.userInstruction.contains("push to github", ignoreCase = true)

        val neededPerms = buildList {
            add(TessaPermissionType.READ_EMAIL)
            add(TessaPermissionType.WRITE_PROJECT)
            if (wantsGitHub) add(TessaPermissionType.PUSH_GITHUB)
        }

        val permDecision = permissionManager.evaluatePermissions(
            taskId = request.taskId,
            userRequest = request.userInstruction,
            requiredPermissions = neededPerms
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

        if (builderHandoff == null || !builderHandoff.isAvailable()) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.FAILED,
                outputSummary = "MAYA Builder handoff is unavailable to process project update for '$projectName'.",
                errorCode = "BUILDER_UNAVAILABLE"
            )
        }

        val handoffReq = TessaToolRequest(
            taskId = request.taskId,
            toolId = MayaBuilderTaskHandoff.TOOL_ID,
            action = "EXECUTE",
            userInstruction = request.userInstruction,
            parameters = mapOf("projectName" to projectName)
        )

        return builderHandoff.execute(handoffReq, permissionManager, config)
    }
}
