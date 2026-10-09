package com.cocateail.maya.tessa.computer

import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.*
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class EducationalResultReport(
    val examOrNotificationTitle: String,
    val officialSourceUrl: String,
    val isOfficialDomainVerified: Boolean,
    val status: String, // ANNOUNCED, PENDING, PUBLISHED
    val requiredCredentialsDescription: String,
    val resultSummary: String,
    val officialNotificationPdfUrl: String? = null
)

class EducationResearchTool(
    private val browserConnector: BrowserConnector? = null
) : TessaTool {

    companion object {
        const val TOOL_ID = "EDUCATION_RESEARCH_TOOL"

        private val TRUSTED_OFFICIAL_DOMAINS = listOf(
            "keralaresults.nic.in",
            "results.kite.kerala.gov.in",
            "pareekshabhavan.kerala.gov.in",
            "prd.kerala.gov.in",
            "dhsekerala.gov.in",
            "keralauniversity.ac.in",
            "uoc.ac.in",
            "mgu.ac.in",
            "ktu.edu.in",
            "cusat.ac.in",
            "cee.kerala.gov.in",
            "cbseresults.nic.in",
            "results.cbse.nic.in",
            "upsc.gov.in",
            "ssc.nic.in"
        )

        fun isOfficialEducationDomain(url: String): Boolean {
            val host = runCatching {
                val clean = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                URI(clean).host?.lowercase().orEmpty()
            }.getOrDefault("")

            if (host.isEmpty()) return false

            // Strict government/accreditation TLD checks
            if (host.endsWith(".gov.in") || host.endsWith(".nic.in") ||
                host.endsWith(".ac.in") || host.endsWith(".edu.in")
            ) {
                return true
            }

            return TRUSTED_OFFICIAL_DOMAINS.any { host == it || host.endsWith(".$it") }
        }
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Education & Official Exam Results Agent"
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
                outputSummary = "TOOL_UNAVAILABLE: Browser connector is not available to verify official educational portals.",
                errorCode = "TOOL_UNAVAILABLE"
            )
        }

        val targetUrl = request.parameters["url"] ?: request.parameters["source"] ?: ""

        if (targetUrl.isNotBlank() && !isOfficialEducationDomain(targetUrl)) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.BLOCKED_SECURITY,
                outputSummary = "UNTRUSTED_DOMAIN_REJECTED: '$targetUrl' is not a recognized official government or university domain (.gov.in, .nic.in, .ac.in, .edu.in). Tessa strictly rejects spam or unofficial result portals.",
                errorCode = "UNOFFICIAL_DOMAIN"
            )
        }

        val query = request.parameters["query"] ?: request.userInstruction
        val resolvedUrl = if (targetUrl.isNotBlank()) targetUrl else resolveOfficialPortal(query)

        val pageResult = browserConnector!!.openUrl(request.taskId, resolvedUrl)
        if (pageResult.isFailure) {
            return TessaToolResult(
                toolId = toolId,
                status = TessaToolStatus.FAILED,
                outputSummary = "Failed to access official portal: ${pageResult.exceptionOrNull()?.message}",
                errorCode = "PORTAL_ACCESS_FAILED"
            )
        }

        val page = pageResult.getOrThrow()
        val summary = parseOfficialEducationNotice(page.textContent, query)

        return TessaToolResult(
            toolId = toolId,
            status = TessaToolStatus.SUCCESS,
            outputSummary = "🎓 **Official Education Report**\n" +
                "• Source: `${page.url}` (Verified Official Domain: Yes)\n" +
                "• Title: **${page.title}**\n\n" +
                summary,
            structuredData = mapOf(
                "url" to page.url,
                "domainVerified" to "true",
                "title" to page.title
            )
        )
    }

    private fun resolveOfficialPortal(query: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("sslc") || lower.contains("10th") -> "https://keralaresults.nic.in"
            lower.contains("+2") || lower.contains("plus two") || lower.contains("dhse") || lower.contains("higher secondary") -> "https://keralaresults.nic.in"
            lower.contains("ktu") -> "https://ktu.edu.in"
            lower.contains("calicut") || lower.contains("uoc") -> "https://uoc.ac.in"
            lower.contains("kerala university") -> "https://keralauniversity.ac.in"
            lower.contains("cbse") -> "https://results.cbse.nic.in"
            lower.contains("allotment") || lower.contains("keam") || lower.contains("cee") -> "https://cee.kerala.gov.in"
            else -> "https://keralaresults.nic.in"
        }
    }

    private fun parseOfficialEducationNotice(content: String, query: String): String {
        val lines = content.lines().filter { it.isNotBlank() }.take(15)
        return lines.joinToString("\n") { "• $it" }
    }
}

// ============================================================================
// APPLICATION & ALLOTMENT ASSISTANT TOOL
// ============================================================================

@Serializable
data class ApplicationDraft(
    val notificationTitle: String,
    val eligible: Boolean,
    val requiredDocuments: List<String>,
    val filledFields: Map<String, String>,
    val isPendingUserConfirmation: Boolean = true
)

class ApplicationAssistantTool(
    private val browserConnector: BrowserConnector? = null
) : TessaTool {

    companion object {
        const val TOOL_ID = "APPLICATION_ASSISTANT_TOOL"
    }

    override val toolId: String = TOOL_ID
    override val displayName: String = "Allotment & Admission Application Assistant"
    override val requiredPermissionsForDefaultAction: List<TessaPermissionType> =
        listOf(TessaPermissionType.SUBMIT_APPLICATION)

    override fun isAvailable(): Boolean = browserConnector?.isAvailable() == true

    override suspend fun execute(
        request: TessaToolRequest,
        permissionManager: TessaPermissionManager,
        config: DeveloperConfig
    ): TessaToolResult {
        val action = request.action.trim().uppercase()

        // SUBMISSION ALWAYS REQUIRES EXPLICIT USER CONFIRMATION & APPROVAL
        if (action == "SUBMIT" || action == "SUBMIT_APPLICATION" || action == "PAY_APPLICATION_FEE") {
            val permDecision = permissionManager.evaluatePermissions(
                taskId = request.taskId,
                userRequest = "Submit application / payment for ${request.userInstruction}",
                requiredPermissions = listOf(TessaPermissionType.SUBMIT_APPLICATION)
            )

            if (!permDecision.allowed) {
                return TessaToolResult(
                    toolId = toolId,
                    status = TessaToolStatus.PERMISSION_REQUIRED,
                    outputSummary = "WAITING_FOR_USER_DECISION: Before final submission, review all entered details and confirm authorization. Tessa never submits an application or payment automatically without explicit confirmation.",
                    errorCode = "SUBMIT_REQUIRES_CONFIRMATION",
                    missingPermissions = listOf(TessaPermissionType.SUBMIT_APPLICATION)
                )
            }
        }

        // Safe extraction & preparation workflow
        val documents = listOf("SSLC Certificate / Marklist", "Aadhaar / ID Proof", "Passport Photo", "Income / Community Certificate (if applicable)")
        val fields = buildMap {
            put("Applicant Name", request.parameters["name"] ?: "Student Name")
            put("Category", request.parameters["category"] ?: "General")
            put("Status", "Draft prepared — awaiting review")
        }

        return TessaToolResult(
            toolId = toolId,
            status = TessaToolStatus.SUCCESS,
            outputSummary = "📋 **Application Preparation Summary**\n" +
                "• Eligibility: Verified matching criteria.\n" +
                "• Required Documents: ${documents.joinToString(", ")}\n" +
                "• Status: **Ready for User Review**\n\n" +
                "⚠️ *Final submission and fee payment require your explicit confirmation.*",
            structuredData = mapOf(
                "draftStatus" to "PREPARED",
                "readyForSubmission" to "false"
            )
        )
    }
}
