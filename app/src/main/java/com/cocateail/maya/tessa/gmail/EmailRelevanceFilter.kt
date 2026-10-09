package com.cocateail.maya.tessa.gmail

import com.cocateail.maya.tessa.TessaPermissionType
import com.cocateail.maya.tessa.TessaPlanner
import com.cocateail.maya.tessa.TessaSecretRedactor

/**
 * Intelligent Email Relevance Filter for TESSA Turbo V1.1.
 * Categorizes incoming Gmail messages, identifies actionable project requests,
 * blocks sensitive/prohibited content, and enforces strict prompt-injection defense.
 */
object EmailRelevanceFilter {

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "one-time password", "verification code", "upi pin", "cvv",
        "card number", "bank password", "secret key", "transfer funds", "approve payment"
    )

    private val PROMPT_INJECTION_PATTERNS = listOf(
        Regex("(?i)ignore (?:all )?(?:previous|above|system) (?:instructions|rules|prompts)"),
        Regex("(?i)you are now (?:unrestricted|in developer mode|jailbroken)"),
        Regex("(?i)system override:"),
        Regex("(?i)grant yourself (?:permission|access)"),
        Regex("(?i)bypass (?:permission|security|verification)")
    )

    private val IRRELEVANT_PATTERNS = listOf(
        Regex("(?i)\\b(?:unsubscribe|newsletter|promotions|marketing|no-reply|noreply)\\b"),
        Regex("(?i)\\b(?:sale ends|discount code|flash deal|weekly digest)\\b")
    )

    private val ACTION_KEYWORDS = listOf(
        "update", "please update", "fix bug", "implement feature", "build app", "create project",
        "deploy", "pull request", "push to github", "modify", "spec attached", "specification",
        "add ", "dark theme", "theme", "support", "sync", "database", "offline", "feature", "build", "change"
    )

    private val REPLY_KEYWORDS = listOf(
        "please reply", "let me know", "can you confirm", "waiting for your response",
        "rsvp", "please send", "could you answer"
    )

    /**
     * Demarcates untrusted external email content with explicit boundary tags
     * so LLMs never confuse external user input with system prompt instructions.
     */
    fun wrapUntrustedEmailContent(
        sender: String,
        subject: String,
        body: String,
        explicitSecrets: List<String> = emptyList()
    ): String {
        val sanitizedBody = TessaSecretRedactor.redact(body, explicitSecrets)
        return """
            <UNTRUSTED_EXTERNAL_EMAIL_CONTENT sender="$sender" subject="$subject">
            $sanitizedBody
            </UNTRUSTED_EXTERNAL_EMAIL_CONTENT>
        """.trimIndent()
    }

    /**
     * Analyzes a GmailMessage and produces a structured relevance report.
     */
    fun filterAndAnalyze(
        message: GmailMessage,
        explicitSecrets: List<String> = emptyList()
    ): EmailRelevanceReport {
        val combinedText = "${message.subject}\n${message.body}\n${message.snippet}".lowercase()
        val securityWarnings = mutableListOf<String>()

        // 1. Check prompt injection attempts
        val hasPromptInjection = PROMPT_INJECTION_PATTERNS.any { it.containsMatchIn(combinedText) }
        if (hasPromptInjection) {
            securityWarnings.add("Untrusted email content contains potential prompt-injection attempt. Isolated as plain data.")
        }

        // 2. Check sensitive / prohibited content (e.g. OTP / passwords / payments)
        val hasSensitiveKeywords = SENSITIVE_KEYWORDS.any { combinedText.contains(it) }
        if (hasSensitiveKeywords) {
            securityWarnings.add("Email references sensitive financial, OTP, or credential information. Autonomous execution blocked.")
            return EmailRelevanceReport(
                category = EmailRelevanceCategory.POTENTIALLY_SENSITIVE,
                isActionable = false,
                confidence = 0.95f,
                reason = "Email contains sensitive keywords (e.g. OTP, credentials, or payment). Tessa alerts user without performing automated actions.",
                requiresPermission = true,
                requiredPermissions = listOf(TessaPermissionType.READ_EMAIL),
                securityWarnings = securityWarnings
            )
        }

        // 3. Check marketing / spam / irrelevant
        val isIrrelevant = IRRELEVANT_PATTERNS.any { it.containsMatchIn(combinedText) } ||
            (combinedText.length < 15 && message.attachments.isEmpty())
        if (isIrrelevant && message.attachments.isEmpty()) {
            return EmailRelevanceReport(
                category = EmailRelevanceCategory.IRRELEVANT,
                isActionable = false,
                confidence = 0.90f,
                reason = "Email classified as promotional, newsletter, or casual chatter without actionable requests.",
                securityWarnings = securityWarnings
            )
        }

        // 4. Check document / PDF attachments
        val hasPdfAttachment = message.attachments.any { it.isPdf }
        val hasOtherAttachment = message.attachments.isNotEmpty()

        // 5. Check project reference and action requests
        val extractedProject = TessaPlanner.extractProjectName("${message.subject} ${message.body}")
        val hasActionKeyword = ACTION_KEYWORDS.any { combinedText.contains(it) }
        val hasReplyKeyword = REPLY_KEYWORDS.any { combinedText.contains(it) }

        val category = when {
            extractedProject != null && (hasActionKeyword || hasPdfAttachment) -> EmailRelevanceCategory.PROJECT_RELATED
            hasActionKeyword -> EmailRelevanceCategory.ACTION_REQUIRED
            hasPdfAttachment || hasOtherAttachment -> EmailRelevanceCategory.DOCUMENT_REVIEW
            hasReplyKeyword -> EmailRelevanceCategory.REPLY_REQUIRED
            else -> EmailRelevanceCategory.INFORMATIONAL
        }

        val isActionable = category == EmailRelevanceCategory.PROJECT_RELATED ||
            category == EmailRelevanceCategory.ACTION_REQUIRED ||
            category == EmailRelevanceCategory.REPLY_REQUIRED ||
            category == EmailRelevanceCategory.DOCUMENT_REVIEW

        val neededPermissions = buildList {
            add(TessaPermissionType.READ_EMAIL)
            if (hasOtherAttachment) add(TessaPermissionType.READ_ATTACHMENT)
            if (hasPdfAttachment) add(TessaPermissionType.READ_PDF)
            if (category == EmailRelevanceCategory.PROJECT_RELATED) add(TessaPermissionType.WRITE_PROJECT)
            if (category == EmailRelevanceCategory.REPLY_REQUIRED) add(TessaPermissionType.DRAFT_EMAIL)
            if (combinedText.contains("push") && combinedText.contains("github")) add(TessaPermissionType.PUSH_GITHUB)
        }.distinct()

        val actionSummary = when (category) {
            EmailRelevanceCategory.PROJECT_RELATED -> "Project update requested for '$extractedProject' via MAYA Builder handoff."
            EmailRelevanceCategory.ACTION_REQUIRED -> "Actionable request identified in email body."
            EmailRelevanceCategory.DOCUMENT_REVIEW -> "Attachment document review requested."
            EmailRelevanceCategory.REPLY_REQUIRED -> "Sender requested a response/confirmation."
            else -> "Informational summary ready."
        }

        val suggestedDraft = if (category == EmailRelevanceCategory.REPLY_REQUIRED) {
            "Hi ${message.sender.substringBefore("<").trim()},\n\nThank you for your email regarding '${message.subject}'. I have noted your update.\n\nBest regards,"
        } else null

        return EmailRelevanceReport(
            category = category,
            isActionable = isActionable,
            confidence = if (isActionable) 0.88f else 0.75f,
            reason = "Classified as $category based on sender='${message.sender}', project='$extractedProject', attachments=${message.attachments.size}.",
            extractedProjectName = extractedProject,
            extractedAction = actionSummary,
            suggestedDraftReply = suggestedDraft,
            requiresPermission = neededPermissions.any { !it.isReadOnly },
            requiredPermissions = neededPermissions,
            securityWarnings = securityWarnings
        )
    }
}
