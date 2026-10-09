package com.cocateail.maya.data.model

import com.cocateail.maya.data.specialist.SpecialistType
import kotlinx.serialization.Serializable

@Serializable
data class DeveloperConfig(
    val systemInstructions: String = "You are MAYA, a warm, intelligent, and helpful AI companion.",
    val personality: String = "Friendly, empathetic, curious, and concise.",
    val responseRules: String = "Be honest about limitations. Provide insightful, polite, and human-like responses.",
    val ttsEnabled: Boolean = true,
    val ttsRate: Float = 0.98f,
    val ttsPitch: Float = 1.05f,
    val faceEnabled: Boolean = true,
    val onlineFeaturesEnabled: Boolean = true,
    val customApiKey: String = "",
    val customEndpoint: String = "",
    val autoDetectSpecialist: Boolean = true,
    val selectedSpecialist: SpecialistType = SpecialistType.GENERAL,
    val maxFixAttempts: Int = 5,
    val qwenCoderModelId: String = "qwen2.5-coder-32b-instruct",
    val qwenCoderEndpoint: String = "https://api.together.xyz/v1/chat/completions",
    val qwenCoderApiKey: String = "",
    val remoteBuildServerUrl: String = "",
    val remoteBuildApiKey: String = "",
    val githubRepository: String = "",
    val githubBranch: String = "main",
    val githubToken: String = "",
    val isCustomerRepoPrivate: Boolean = false,
    val lastPushedCommitSha: String = "",
    val allowDeveloperRepoFallback: Boolean = false,
    val cloudRunBuildUrl: String = "",
    val cloudRunApiKey: String = "",
    val cloudRunTimeoutSec: Int = 300,
    val backendSelectionPolicy: String = "AUTO", // AUTO, GITHUB_ACTIONS, CLOUD_RUN
    val preferredArtifactType: String = "APK", // APK, AAB, WEB
    val activeAssistantMode: String = "MAYA_ONLINE", // MAYA_ONLINE, MAYA_OFFLINE, TESSA_TURBO_V1, SIMBA_TURBO_V2
    val tessaModelId: String = DEFAULT_TESSA_MODEL_ID,
    val tessaApiBaseUrl: String = DEFAULT_TESSA_BASE_URL,
    val tessaApiKey: String = DEFAULT_TESSA_API_KEY,
    val gmailConnectedEmail: String = "",
    val gmailOAuthAccessToken: String = "",
    val gmailOAuthRefreshToken: String = "",
    val gmailTokenExpiryEpochMs: Long = 0L,
    val gmailMonitoringEnabled: Boolean = false,
    val gmailMonitoringIntervalMinutes: Long = 60L,
    val developerUnlocked: Boolean = false
) {
    companion object {
        const val DEFAULT_TESSA_MODEL_ID = "gemini-3.8-flash"
        const val DEFAULT_TESSA_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        val DEFAULT_TESSA_API_KEY: String
            get() {
                val envSecret = runCatching { System.getenv("TESSA_API_KEY")?.trim().orEmpty() }.getOrDefault("")
                if (!isPlaceholderKey(envSecret)) {
                    return envSecret
                }
                val tessaSecret = runCatching { com.cocateail.maya.BuildConfig.TESSA_API_KEY.trim() }.getOrDefault("")
                if (!isPlaceholderKey(tessaSecret)) {
                    return tessaSecret
                }
                return ""
            }

        private val OBSOLETE_GEMINI_MODELS = setOf(
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemini-pro",
            "gemini-2.0-flash",
            "gemini-2.0-pro",
            "gemini-2.0-flash-thinking",
            "gemini-3-flash-preview"
        )

        fun isObsoleteGeminiModel(modelId: String): Boolean {
            val clean = modelId.trim().removePrefix("models/").lowercase()
            return clean in OBSOLETE_GEMINI_MODELS
        }

        fun isPlaceholderKey(key: String): Boolean {
            val k = key.trim()
            return k.isBlank() ||
                k.equals("your_tessa_api_key_here", ignoreCase = true) ||
                k.equals("your_gemini_api_key_here", ignoreCase = true)
        }
    }

    fun resolvedCloudRunUrl(): String = cloudRunBuildUrl.ifBlank { remoteBuildServerUrl }.trim()
    fun resolvedCloudRunApiKey(): String = cloudRunApiKey.ifBlank { remoteBuildApiKey }.trim()

    /**
     * Resolves the TESSA-specific API key from DeveloperConfig.
     * NEVER reuses MAYA Online's GEMINI_API_KEY or customApiKey.
     */
    fun resolvedTessaApiKey(): String {
        val explicit = tessaApiKey.trim()
        return if (!isPlaceholderKey(explicit)) {
            explicit
        } else {
            ""
        }
    }

    fun isTessaProviderConfigured(): Boolean =
        tessaModelId.isNotBlank() && tessaApiBaseUrl.isNotBlank() && resolvedTessaApiKey().isNotBlank()

    fun isGmailConnected(): Boolean =
        gmailConnectedEmail.isNotBlank() && (gmailOAuthAccessToken.isNotBlank() || gmailOAuthRefreshToken.isNotBlank())
}

enum class MayaCharacter(val codeName: String, val displayName: String, val description: String, val avatarLetter: String) {
    MAYA("MAYA", "MAYA", "Your warm & empathetic AI companion", "M"),
    ROCKY("ROCKY", "ROCKY", "Your witty & energetic AI sidekick", "R")
}
