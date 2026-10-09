package com.cocateail.maya.tessa.gmail

import android.content.Context
import android.util.Log
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.TessaSecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

interface GmailConnector {
    val isConnected: Boolean
    val connectedEmail: String?

    suspend fun getProfile(): Result<GmailProfile>
    suspend fun listMessages(query: String? = null, maxResults: Int = 20, pageToken: String? = null): Result<List<GmailMessageSummary>>
    suspend fun getMessage(messageId: String, format: String = "full"): Result<GmailMessage>
    suspend fun getAttachment(messageId: String, attachmentId: String, filename: String): Result<ByteArray>
    suspend fun downloadAttachmentToCache(messageId: String, attachmentId: String, filename: String): Result<File>
    suspend fun createDraft(to: String, subject: String, body: String, threadId: String? = null): Result<GmailDraftResult>
    suspend fun sendMessage(to: String, subject: String, body: String, threadId: String? = null): Result<GmailSendResult>
    fun disconnect()
}

/**
 * Production implementation of GmailConnector connecting to Google's official Gmail REST API.
 * Base URL: https://gmail.googleapis.com/gmail/v1/users/me
 */
class RealGmailConnector(
    private val context: Context? = null,
    private val configProvider: () -> DeveloperConfig,
    private val onConfigUpdated: ((DeveloperConfig) -> Unit)? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : GmailConnector {

    companion object {
        private const val TAG = "RealGmailConnector"
        private const val GMAIL_API_BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val MAX_ATTACHMENT_SIZE_BYTES = 25L * 1024 * 1024 // 25 MB Gmail limit

        /**
         * Sanitizes attachment filenames against directory traversal attacks.
         */
        fun sanitizeAttachmentFilename(rawName: String): String {
            val normalized = rawName.trim().replace('\\', '/')
            val baseName = normalized.substringAfterLast('/')
            val clean = baseName.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            return if (clean.isBlank() || clean == "." || clean == "..") {
                "attachment_${UUID.randomUUID().toString().take(8)}.bin"
            } else {
                clean
            }
        }

        /**
         * Constructs an RFC 2822 formatted raw message and encodes it with URL-safe Base64 (no padding).
         */
        fun buildRfc2822RawMessage(
            to: String,
            subject: String,
            body: String,
            threadId: String? = null
        ): String {
            val raw = buildString {
                append("To: ${to.trim()}\r\n")
                append("Subject: ${subject.trim()}\r\n")
                append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
                append("MIME-Version: 1.0\r\n")
                if (!threadId.isNullOrBlank()) {
                    append("References: <${threadId.trim()}@mail.gmail.com>\r\n")
                    append("In-Reply-To: <${threadId.trim()}@mail.gmail.com>\r\n")
                }
                append("\r\n")
                append(body)
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
        }
    }

    override val isConnected: Boolean
        get() {
            val cfg = configProvider()
            return cfg.isGmailConnected()
        }

    override val connectedEmail: String?
        get() {
            val cfg = configProvider()
            return cfg.gmailConnectedEmail.ifBlank { null }
        }

    private fun getAccessToken(): String {
        val cfg = configProvider()
        val token = cfg.gmailOAuthAccessToken.ifBlank { cfg.gmailOAuthRefreshToken }
        if (token.isBlank()) {
            throw GmailException(
                GmailErrorCode.GMAIL_NOT_CONNECTED,
                "Gmail account is not connected. Please authenticate with Google OAuth."
            )
        }
        if (cfg.gmailTokenExpiryEpochMs > 0 && System.currentTimeMillis() >= cfg.gmailTokenExpiryEpochMs) {
            throw GmailException(
                GmailErrorCode.TOKEN_EXPIRED,
                "Gmail OAuth access token has expired. Please refresh credentials."
            )
        }
        return token.trim()
    }

    private fun buildAuthenticatedRequest(endpoint: String, method: String = "GET", body: okhttp3.RequestBody? = null): Request {
        val token = getAccessToken()
        val url = if (endpoint.startsWith("http")) endpoint else "$GMAIL_API_BASE_URL/$endpoint"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, body)
            .build()
    }

    private fun handleHttpError(response: Response): Nothing {
        val code = response.code
        val errorBody = response.body?.string().orEmpty()
        val redactedBody = TessaSecretRedactor.redact(errorBody)

        val errorCode = when (code) {
            401 -> GmailErrorCode.TOKEN_EXPIRED
            403 -> {
                if (redactedBody.contains("rateLimitExceeded", ignoreCase = true) || redactedBody.contains("userRateLimitExceeded", ignoreCase = true)) {
                    GmailErrorCode.RATE_LIMIT
                } else {
                    GmailErrorCode.INSUFFICIENT_SCOPE
                }
            }
            404 -> GmailErrorCode.MESSAGE_NOT_FOUND
            429 -> GmailErrorCode.RATE_LIMIT
            else -> GmailErrorCode.GMAIL_API_ERROR
        }

        throw GmailException(
            errorCode,
            "Gmail API request failed with HTTP $code: $redactedBody"
        )
    }

    override suspend fun getProfile(): Result<GmailProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val request = buildAuthenticatedRequest("profile")
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) handleHttpError(resp)
                val json = JSONObject(resp.body?.string() ?: "{}")
                GmailProfile(
                    emailAddress = json.optString("emailAddress", connectedEmail ?: ""),
                    messagesTotal = json.optLong("messagesTotal", 0L),
                    threadsTotal = json.optLong("threadsTotal", 0L),
                    historyId = json.optString("historyId", "")
                )
            }
        }
    }

    override suspend fun listMessages(
        query: String?,
        maxResults: Int,
        pageToken: String?
    ): Result<List<GmailMessageSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val queryParams = buildList {
                add("maxResults=${maxResults.coerceIn(1, 100)}")
                if (!query.isNullOrBlank()) add("q=${java.net.URLEncoder.encode(query, "UTF-8")}")
                if (!pageToken.isNullOrBlank()) add("pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}")
            }.joinToString("&")

            val listRequest = buildAuthenticatedRequest("messages?$queryParams")
            val idList = httpClient.newCall(listRequest).execute().use { resp ->
                if (!resp.isSuccessful) handleHttpError(resp)
                val json = JSONObject(resp.body?.string() ?: "{}")
                val messagesArray = json.optJSONArray("messages") ?: JSONArray()
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until messagesArray.length()) {
                    val item = messagesArray.getJSONObject(i)
                    val id = item.optString("id")
                    val threadId = item.optString("threadId", id)
                    if (id.isNotBlank()) list.add(id to threadId)
                }
                list
            }

            // Fetch details for each message summary
            idList.mapNotNull { (msgId, _) ->
                try {
                    val detailReq = buildAuthenticatedRequest("messages/$msgId?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date")
                    httpClient.newCall(detailReq).execute().use { resp ->
                        if (!resp.isSuccessful) null
                        else {
                            val json = JSONObject(resp.body?.string() ?: "{}")
                            parseMessageSummary(json)
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun parseMessageSummary(json: JSONObject): GmailMessageSummary {
        val id = json.optString("id")
        val threadId = json.optString("threadId", id)
        val snippet = json.optString("snippet", "")
        val internalDate = json.optLong("internalDate", System.currentTimeMillis())

        val labels = mutableListOf<String>()
        val labelsArr = json.optJSONArray("labelIds")
        if (labelsArr != null) {
            for (i in 0 until labelsArr.length()) {
                labels.add(labelsArr.getString(i))
            }
        }

        var subject = "(No Subject)"
        var sender = "(Unknown Sender)"

        val payload = json.optJSONObject("payload")
        val headers = payload?.optJSONArray("headers")
        if (headers != null) {
            for (i in 0 until headers.length()) {
                val header = headers.getJSONObject(i)
                val name = header.optString("name")
                val value = header.optString("value")
                when {
                    name.equals("Subject", ignoreCase = true) -> subject = value
                    name.equals("From", ignoreCase = true) -> sender = value
                }
            }
        }

        var hasAttachments = false
        val parts = payload?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val filename = part.optString("filename")
                if (filename.isNotBlank()) {
                    hasAttachments = true
                    break
                }
            }
        }

        return GmailMessageSummary(
            id = id,
            threadId = threadId,
            sender = sender,
            subject = subject,
            timestamp = internalDate,
            snippet = snippet,
            hasAttachments = hasAttachments,
            labelIds = labels
        )
    }

    override suspend fun getMessage(messageId: String, format: String): Result<GmailMessage> = withContext(Dispatchers.IO) {
        runCatching {
            if (messageId.isBlank()) {
                throw GmailException(GmailErrorCode.MESSAGE_NOT_FOUND, "Message ID cannot be empty.")
            }
            val request = buildAuthenticatedRequest("messages/$messageId?format=$format")
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) handleHttpError(resp)
                val json = JSONObject(resp.body?.string() ?: "{}")
                parseFullMessage(json)
            }
        }
    }

    private fun parseFullMessage(json: JSONObject): GmailMessage {
        val id = json.optString("id")
        val threadId = json.optString("threadId", id)
        val snippet = json.optString("snippet", "")
        val internalDate = json.optLong("internalDate", System.currentTimeMillis())

        var subject = "(No Subject)"
        var sender = "(Unknown Sender)"
        val recipients = mutableListOf<String>()

        val payload = json.optJSONObject("payload")
        val rawMimeType = payload?.optString("mimeType")
        val headers = payload?.optJSONArray("headers")
        if (headers != null) {
            for (i in 0 until headers.length()) {
                val header = headers.getJSONObject(i)
                val name = header.optString("name")
                val value = header.optString("value")
                when {
                    name.equals("Subject", ignoreCase = true) -> subject = value
                    name.equals("From", ignoreCase = true) -> sender = value
                    name.equals("To", ignoreCase = true) -> recipients.add(value)
                    name.equals("Cc", ignoreCase = true) -> recipients.add(value)
                }
            }
        }

        val bodyBuilder = StringBuilder()
        val attachments = mutableListOf<GmailAttachmentMetadata>()

        fun extractPart(part: JSONObject) {
            val mime = part.optString("mimeType", "")
            val filename = part.optString("filename", "")
            val bodyObj = part.optJSONObject("body")
            val attachmentId = bodyObj?.optString("attachmentId")
            val size = bodyObj?.optLong("size", 0L) ?: 0L

            if (filename.isNotBlank() && !attachmentId.isNullOrBlank()) {
                attachments.add(
                    GmailAttachmentMetadata(
                        attachmentId = attachmentId,
                        filename = sanitizeAttachmentFilename(filename),
                        mimeType = mime,
                        sizeBytes = size
                    )
                )
            } else if (bodyObj != null && bodyObj.has("data")) {
                val rawData = bodyObj.optString("data", "")
                if (rawData.isNotBlank()) {
                    val decoded = runCatching {
                        String(Base64.getUrlDecoder().decode(rawData), Charsets.UTF_8)
                    }.getOrNull()
                    if (decoded != null && (mime.startsWith("text/plain") || bodyBuilder.isEmpty())) {
                        bodyBuilder.append(decoded).append("\n")
                    }
                }
            }

            val childParts = part.optJSONArray("parts")
            if (childParts != null) {
                for (i in 0 until childParts.length()) {
                    extractPart(childParts.getJSONObject(i))
                }
            }
        }

        if (payload != null) {
            extractPart(payload)
        }

        val finalBody = if (bodyBuilder.isNotBlank()) {
            bodyBuilder.toString().trim()
        } else {
            snippet
        }

        return GmailMessage(
            id = id,
            threadId = threadId,
            sender = sender,
            recipients = recipients,
            subject = subject,
            body = finalBody,
            snippet = snippet,
            timestamp = internalDate,
            attachments = attachments,
            rawMimeType = rawMimeType
        )
    }

    override suspend fun getAttachment(
        messageId: String,
        attachmentId: String,
        filename: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            if (messageId.isBlank() || attachmentId.isBlank()) {
                throw GmailException(GmailErrorCode.ATTACHMENT_NOT_FOUND, "Message ID and Attachment ID are required.")
            }
            val request = buildAuthenticatedRequest("messages/$messageId/attachments/$attachmentId")
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) handleHttpError(resp)
                val json = JSONObject(resp.body?.string() ?: "{}")
                val dataBase64Url = json.optString("data")
                val size = json.optLong("size", 0L)
                if (size > MAX_ATTACHMENT_SIZE_BYTES) {
                    throw GmailException(
                        GmailErrorCode.ATTACHMENT_TOO_LARGE,
                        "Attachment exceeds maximum allowed size of 25MB (size: $size bytes)."
                    )
                }
                if (dataBase64Url.isBlank()) {
                    throw GmailException(GmailErrorCode.ATTACHMENT_NOT_FOUND, "Attachment payload was empty.")
                }
                Base64.getUrlDecoder().decode(dataBase64Url)
            }
        }
    }

    override suspend fun downloadAttachmentToCache(
        messageId: String,
        attachmentId: String,
        filename: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = getAttachment(messageId, attachmentId, filename).getOrThrow()
            val safeName = sanitizeAttachmentFilename(filename)
            val cacheDir = File(context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."), "tessa_gmail_attachments")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val tempFile = File(cacheDir, "${UUID.randomUUID().toString().take(8)}_$safeName")
            FileOutputStream(tempFile).use { it.write(bytes) }
            tempFile
        }
    }

    override suspend fun createDraft(
        to: String,
        subject: String,
        body: String,
        threadId: String?
    ): Result<GmailDraftResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (to.isBlank()) {
                throw GmailException(GmailErrorCode.GMAIL_API_ERROR, "Recipient email address ('to') cannot be empty.")
            }
            val rawMessage = buildRfc2822RawMessage(to, subject, body, threadId)
            val messageJson = JSONObject().apply {
                put("raw", rawMessage)
                if (!threadId.isNullOrBlank()) {
                    put("threadId", threadId)
                }
            }
            val payload = JSONObject().apply {
                put("message", messageJson)
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = buildAuthenticatedRequest("drafts", method = "POST", body = requestBody)

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) handleHttpError(resp)
                val json = JSONObject(resp.body?.string() ?: "{}")
                val draftId = json.optString("id", UUID.randomUUID().toString())
                val messageObj = json.optJSONObject("message")
                val msgId = messageObj?.optString("id")
                val tId = messageObj?.optString("threadId", threadId.orEmpty())

                GmailDraftResult(
                    draftId = draftId,
                    messageId = msgId,
                    threadId = tId?.ifBlank { threadId },
                    recipient = to,
                    subject = subject,
                    bodyPreview = body.take(200)
                )
            }
        }
    }

    override suspend fun sendMessage(
        to: String,
        subject: String,
        body: String,
        threadId: String?
    ): Result<GmailSendResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (to.isBlank()) {
                throw GmailException(GmailErrorCode.GMAIL_API_ERROR, "Recipient email address ('to') cannot be empty.")
            }
            val rawMessage = buildRfc2822RawMessage(to, subject, body, threadId)
            val payload = JSONObject().apply {
                put("raw", rawMessage)
                if (!threadId.isNullOrBlank()) {
                    put("threadId", threadId)
                }
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = buildAuthenticatedRequest("messages/send", method = "POST", body = requestBody)

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) handleHttpError(resp)
                val json = JSONObject(resp.body?.string() ?: "{}")
                val messageId = json.optString("id", UUID.randomUUID().toString())
                val tId = json.optString("threadId", threadId.orEmpty())

                GmailSendResult(
                    messageId = messageId,
                    threadId = tId?.ifBlank { threadId },
                    recipient = to,
                    subject = subject,
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }

    override fun disconnect() {
        val current = configProvider()
        val updated = current.copy(
            gmailConnectedEmail = "",
            gmailOAuthAccessToken = "",
            gmailOAuthRefreshToken = "",
            gmailTokenExpiryEpochMs = 0L,
            gmailMonitoringEnabled = false
        )
        onConfigUpdated?.invoke(updated)
        runCatching { Log.i(TAG, "Gmail account disconnected and credentials cleared.") }
    }
}
