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
    val maxFixAttempts: Int = 3,
    val qwenCoderModelId: String = "qwen2.5-coder-32b-instruct",
    val qwenCoderEndpoint: String = "https://api.together.xyz/v1/chat/completions",
    val qwenCoderApiKey: String = "",
    val remoteBuildServerUrl: String = "",
    val remoteBuildApiKey: String = ""
)

enum class MayaCharacter(val codeName: String, val displayName: String, val description: String, val avatarLetter: String) {
    MAYA("MAYA", "MAYA", "Your warm & empathetic AI companion", "M"),
    ROCKY("ROCKY", "ROCKY", "Your witty & energetic AI sidekick", "R")
}
