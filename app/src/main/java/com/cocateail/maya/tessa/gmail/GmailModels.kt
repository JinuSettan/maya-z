package com.cocateail.maya.tessa.gmail

import com.cocateail.maya.tessa.TessaPermissionType
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Authentication and connection state for the official Google Gmail OAuth integration.
 */
@Serializable
data class GmailAuthState(
    val isConnected: Boolean,
    val accountEmail: String? = null,
    val grantedScopes: List<String> = emptyList(),
    val tokenExpiryEpochMs: Long = 0L,
    val lastSyncTimestamp: Long = 0L
) {
    val isExpired: Boolean
        get() = tokenExpiryEpochMs > 0 && System.currentTimeMillis() >= tokenExpiryEpochMs
}

/**
 * User profile from official Gmail API GET /gmail/v1/users/me/profile
 */
@Serializable
data class GmailProfile(
    val emailAddress: String,
    val messagesTotal: Long = 0L,
    val threadsTotal: Long = 0L,
    val historyId: String = ""
)

/**
 * Summary of a Gmail message for inbox scanning and list views.
 */
@Serializable
data class GmailMessageSummary(
    val id: String,
    val threadId: String,
    val sender: String,
    val subject: String,
    val timestamp: Long,
    val snippet: String,
    val hasAttachments: Boolean,
    val labelIds: List<String> = emptyList()
)

/**
 * Attachment metadata with sanitized safe filename and validated size.
 */
@Serializable
data class GmailAttachmentMetadata(
    val attachmentId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localCachePath: String? = null
) {
    val isPdf: Boolean
        get() = mimeType.equals("application/pdf", ignoreCase = true) || filename.endsWith(".pdf", ignoreCase = true)
}

/**
 * Full message payload from GET /gmail/v1/users/me/messages/{id}?format=full
 */
@Serializable
data class GmailMessage(
    val id: String,
    val threadId: String,
    val sender: String,
    val recipients: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val snippet: String,
    val timestamp: Long,
    val attachments: List<GmailAttachmentMetadata> = emptyList(),
    val rawMimeType: String? = null
)

/**
 * Result of creating a draft in Gmail.
 */
@Serializable
data class GmailDraftResult(
    val draftId: String,
    val messageId: String? = null,
    val threadId: String? = null,
    val recipient: String,
    val subject: String,
    val bodyPreview: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Result of sending an email via Gmail API.
 */
@Serializable
data class GmailSendResult(
    val messageId: String,
    val threadId: String? = null,
    val recipient: String,
    val subject: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Email relevance categories to prevent treating every incoming email as an autonomous task.
 */
enum class EmailRelevanceCategory {
    INFORMATIONAL,
    PROJECT_RELATED,
    ACTION_REQUIRED,
    REPLY_REQUIRED,
    DOCUMENT_REVIEW,
    IRRELEVANT,
    POTENTIALLY_SENSITIVE
}

/**
 * Structured report from EmailRelevanceFilter.
 */
@Serializable
data class EmailRelevanceReport(
    val category: EmailRelevanceCategory,
    val isActionable: Boolean,
    val confidence: Float,
    val reason: String,
    val extractedProjectName: String? = null,
    val extractedAction: String? = null,
    val suggestedDraftReply: String? = null,
    val requiresPermission: Boolean = false,
    val requiredPermissions: List<TessaPermissionType> = emptyList(),
    val securityWarnings: List<String> = emptyList()
)

/**
 * Specific Gmail integration error codes for failure handling.
 */
enum class GmailErrorCode {
    GMAIL_NOT_CONNECTED,
    OAUTH_FAILED,
    TOKEN_EXPIRED,
    INSUFFICIENT_SCOPE,
    GMAIL_API_ERROR,
    MESSAGE_NOT_FOUND,
    ATTACHMENT_NOT_FOUND,
    ATTACHMENT_TOO_LARGE,
    ATTACHMENT_UNSAFE_PATH,
    PDF_PARSE_FAILED,
    TESSA_MODEL_UNAVAILABLE,
    PERMISSION_REQUIRED,
    PROJECT_NOT_FOUND,
    BUILDER_FAILED,
    TASK_CANCELLED,
    RATE_LIMIT,
    NETWORK_UNAVAILABLE,
    PROMPT_INJECTION_DETECTED,
    SENSITIVE_CONTENT_BLOCKED
}

class GmailException(
    val errorCode: GmailErrorCode,
    message: String,
    cause: Throwable? = null
) : Exception("[$errorCode] $message", cause)
