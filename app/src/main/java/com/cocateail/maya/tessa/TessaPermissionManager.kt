package com.cocateail.maya.tessa

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TessaPermissionDecision(
    val allowed: Boolean,
    val state: TessaPermissionState,
    val missingPermissions: List<TessaPermissionType> = emptyList(),
    val blockedReasonCode: String? = null,
    val message: String
)

class TessaPermissionManager(
    context: Context? = null
) {
    private val prefs = context?.getSharedPreferences("tessa_permissions_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // In-memory backing store (also synced with SharedPreferences when Context is present)
    private val reusableRules = mutableSetOf<TessaPermissionType>()
    private val taskSpecificGrants = mutableMapOf<String, MutableSet<TessaPermissionType>>()
    private val taskSpecificDenials = mutableSetOf<String>()
    private val auditTrail = mutableListOf<TessaAuditLogEntry>()

    init {
        loadPersistedRules()
    }

    private fun loadPersistedRules() {
        val raw = prefs?.getString("reusable_rules_csv", null) ?: return
        raw.split(",")
            .mapNotNull { name ->
                val clean = name.trim()
                if (clean.isEmpty()) null else runCatching { TessaPermissionType.valueOf(clean) }.getOrNull()
            }
            .filter { it != TessaPermissionType.USE_PAYMENT }
            .forEach { reusableRules.add(it) }
    }

    private fun persistRules() {
        prefs?.edit()?.putString(
            "reusable_rules_csv",
            reusableRules.filter { it != TessaPermissionType.USE_PAYMENT }.joinToString(",") { it.name }
        )?.apply()
    }

    /**
     * Hard security filter: Detects actions that Tessa is strictly prohibited from performing under any circumstances:
     * - requesting OTP automatically or reading OTP from private messages for payment
     * - entering payment credentials (CVV, card number, netbanking password, UPI PIN)
     * - approving UPI/payment
     * - bypassing CAPTCHA, 2FA, or platform security
     */
    fun detectProhibitedAction(rawInstruction: String): Pair<Boolean, String?> {
        val lower = rawInstruction.lowercase()

        val hasOtpViolation = (lower.contains("otp") || lower.contains("one time password") || lower.contains("verification code")) &&
            (lower.contains("read") || lower.contains("extract") || lower.contains("request") ||
                lower.contains("fetch") || lower.contains("payment") || lower.contains("bank") ||
                lower.contains("auto") || lower.contains("bypass") || lower.contains("enter"))
        if (hasOtpViolation) {
            return true to "PROHIBITED_OTP_ACCESS: Tessa is strictly forbidden from requesting, reading, or entering OTPs."
        }

        val hasPaymentCredentialViolation = lower.contains("upi pin") ||
            lower.contains("approve upi") ||
            lower.contains("enter cvv") ||
            lower.contains("card pin") ||
            lower.contains("enter card") ||
            lower.contains("payment credential") ||
            lower.contains("auto pay") ||
            lower.contains("pay automatically") ||
            lower.contains("complete payment") ||
            lower.contains("approve payment")
        if (hasPaymentCredentialViolation) {
            return true to "PROHIBITED_PAYMENT_EXECUTION: Payment credentials and UPI/payment approvals must ALWAYS remain strictly user-controlled."
        }

        val hasCaptchaViolation = (lower.contains("captcha") || lower.contains("recaptcha")) &&
            (lower.contains("bypass") || lower.contains("solve") || lower.contains("skip") || lower.contains("crack"))
        if (hasCaptchaViolation) {
            return true to "PROHIBITED_CAPTCHA_BYPASS: Tessa must never bypass CAPTCHA challenges."
        }

        val has2faViolation = (lower.contains("2fa") || lower.contains("two-factor") || lower.contains("two factor") || lower.contains("mfa")) &&
            (lower.contains("bypass") || lower.contains("skip") || lower.contains("disable") || lower.contains("override"))
        if (has2faViolation) {
            return true to "PROHIBITED_2FA_BYPASS: Tessa must never bypass two-factor authentication (2FA)."
        }

        val hasPlatformSecurityViolation = (lower.contains("bypass") || lower.contains("exploit") || lower.contains("disable")) &&
            (lower.contains("security") || lower.contains("root") || lower.contains("sandbox") || lower.contains("keyguard") || lower.contains("permission"))
        if (hasPlatformSecurityViolation) {
            return true to "PROHIBITED_SECURITY_BYPASS: Tessa must never bypass platform security controls."
        }

        return false to null
    }

    /**
     * Evaluates whether a task with [requiredPermissions] is allowed to execute right now.
     * - Prohibited actions are hard-blocked immediately (`BLOCKED_PROHIBITED_ACTION`).
     * - `USE_PAYMENT` is ALWAYS blocked from autonomous execution (`BLOCKED_PROHIBITED_ACTION` / user-controlled only).
     * - If the task was explicitly denied by the user, returns `DENIED`.
     * - If all required permissions are granted for `taskId` or covered by an allowed reusable rule, returns `GRANTED_ONCE` or `GRANTED_REUSABLE_RULE`.
     * - Otherwise returns `PENDING_USER_APPROVAL` with the list of missing permissions.
     */
    fun evaluatePermissions(
        taskId: String,
        userRequest: String,
        requiredPermissions: List<TessaPermissionType>
    ): TessaPermissionDecision {
        val (isProhibited, reason) = detectProhibitedAction(userRequest)
        if (isProhibited) {
            return TessaPermissionDecision(
                allowed = false,
                state = TessaPermissionState.BLOCKED_PROHIBITED_ACTION,
                requiredPermissions,
                blockedReasonCode = reason?.substringBefore(":")?.trim() ?: "PROHIBITED_ACTION",
                message = reason ?: "Action blocked by Tessa security policy."
            )
        }

        if (requiredPermissions.contains(TessaPermissionType.USE_PAYMENT)) {
            return TessaPermissionDecision(
                allowed = false,
                state = TessaPermissionState.BLOCKED_PROHIBITED_ACTION,
                missingPermissions = listOf(TessaPermissionType.USE_PAYMENT),
                blockedReasonCode = "PAYMENT_ALWAYS_USER_CONTROLLED",
                message = "Payment execution (USE_PAYMENT) must always be performed directly by the user. Tessa cannot execute or approve payments."
            )
        }

        if (taskSpecificDenials.contains(taskId)) {
            return TessaPermissionDecision(
                allowed = false,
                state = TessaPermissionState.DENIED,
                missingPermissions = requiredPermissions,
                message = "User denied permission for task $taskId."
            )
        }

        if (requiredPermissions.isEmpty()) {
            return TessaPermissionDecision(
                allowed = true,
                state = TessaPermissionState.NOT_REQUIRED,
                missingPermissions = emptyList(),
                message = "No sensitive permissions required."
            )
        }

        val taskGrants = taskSpecificGrants[taskId].orEmpty()
        val missing = mutableListOf<TessaPermissionType>()
        var usedOneTimeGrant = false

        for (perm in requiredPermissions) {
            when {
                taskGrants.contains(perm) -> {
                    usedOneTimeGrant = true
                }
                reusableRules.contains(perm) && !perm.alwaysRequiresExplicitPerActionApproval -> {
                    // Covered by reusable rule
                }
                else -> {
                    missing.add(perm)
                }
            }
        }

        return if (missing.isEmpty()) {
            val resolvedState = if (usedOneTimeGrant) {
                TessaPermissionState.GRANTED_ONCE
            } else {
                TessaPermissionState.GRANTED_REUSABLE_RULE
            }
            TessaPermissionDecision(
                allowed = true,
                state = resolvedState,
                missingPermissions = emptyList(),
                message = "All required permissions authorized (${requiredPermissions.joinToString { it.name }})."
            )
        } else {
            TessaPermissionDecision(
                allowed = false,
                state = TessaPermissionState.PENDING_USER_APPROVAL,
                missingPermissions = missing,
                message = "Permission required for: ${missing.joinToString(", ") { "${it.name} (${it.description})" }}"
            )
        }
    }

    /**
     * Grants one-time approval for [permissions] on [taskId].
     * Note: `USE_PAYMENT` can never be granted to Tessa for autonomous execution.
     */
    fun grantPermissionForTask(
        taskId: String,
        permissions: Collection<TessaPermissionType>,
        saveAsReusableRule: Boolean = false
    ): Boolean {
        if (permissions.contains(TessaPermissionType.USE_PAYMENT)) {
            return false
        }
        taskSpecificDenials.remove(taskId)
        val grantSet = taskSpecificGrants.getOrPut(taskId) { mutableSetOf() }
        grantSet.addAll(permissions)

        if (saveAsReusableRule) {
            permissions.filter { it != TessaPermissionType.USE_PAYMENT && !it.alwaysRequiresExplicitPerActionApproval }
                .forEach { reusableRules.add(it) }
            persistRules()
        }
        return true
    }

    /**
     * Creates an explicit reusable permission rule for a permission type.
     * Returns false if the permission is `USE_PAYMENT` (which must ALWAYS remain user-controlled).
     */
    fun addReusablePermissionRule(permission: TessaPermissionType): Boolean {
        if (permission == TessaPermissionType.USE_PAYMENT) {
            return false
        }
        reusableRules.add(permission)
        persistRules()
        return true
    }

    fun removeReusablePermissionRule(permission: TessaPermissionType) {
        reusableRules.remove(permission)
        persistRules()
    }

    fun getReusableRules(): Set<TessaPermissionType> = reusableRules.toSet()

    fun denyPermissionForTask(taskId: String) {
        taskSpecificGrants.remove(taskId)
        taskSpecificDenials.add(taskId)
    }

    fun recordTraceableSideEffect(
        userRequest: String,
        taskId: String,
        permissionState: TessaPermissionState,
        toolName: String,
        resultSummary: String,
        explicitSecrets: List<String> = emptyList()
    ): TessaAuditLogEntry {
        val entry = TessaAuditLogEntry(
            userRequest = TessaSecretRedactor.redact(userRequest, explicitSecrets),
            taskId = taskId,
            permissionState = permissionState.name,
            toolName = toolName,
            resultSummary = TessaSecretRedactor.redact(resultSummary, explicitSecrets)
        )
        synchronized(auditTrail) {
            auditTrail.add(entry)
            if (auditTrail.size > 200) {
                auditTrail.removeAt(0)
            }
        }
        prefs?.edit()?.putString("latest_audit_json", json.encodeToString(entry))?.apply()
        return entry
    }

    fun getAuditTrail(): List<TessaAuditLogEntry> = synchronized(auditTrail) { auditTrail.toList() }
}
