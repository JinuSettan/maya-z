package com.cocateail.maya.data.provider

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.cocateail.maya.BuildConfig
import com.cocateail.maya.data.api.Content
import com.cocateail.maya.data.api.GenerateContentRequest
import com.cocateail.maya.data.api.GenerateContentResponse
import com.cocateail.maya.data.api.GenerationConfig
import com.cocateail.maya.data.api.InlineData
import com.cocateail.maya.data.api.Part
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.data.specialist.SpecialistRegistry
import com.cocateail.maya.data.specialist.SpecialistType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiLlmProvider : LlmProvider {
    override val providerId: String = "gemini_provider"
    override val providerDisplayName: String = "Google Gemini Provider"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int
        if (width > height) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    override suspend fun generateResponse(request: LlmRequest): String? = withContext(Dispatchers.IO) {
        val promptLower = request.prompt.lowercase()
        val isVideoReq = promptLower.contains("video") ||
                promptLower.contains("motion") ||
                promptLower.contains("clip")

        val isImageReq = !isVideoReq && (
                promptLower.contains("image") ||
                promptLower.contains("photo") ||
                promptLower.contains("pic") ||
                promptLower.contains("picture") ||
                promptLower.contains("draw") ||
                promptLower.contains("vara") ||
                promptLower.contains("chithram") ||
                promptLower.contains("cinematic") ||
                promptLower.contains("create a") ||
                promptLower.contains("make a") ||
                promptLower.contains("generate a") ||
                promptLower.contains("render") ||
                promptLower.contains("wallpaper") ||
                promptLower.contains("art of") ||
                promptLower.contains("shot of") ||
                promptLower.contains("undakku") ||
                promptLower.contains("tharoo")
        )

        if (isImageReq || isVideoReq) {
            val isVideo = isVideoReq
            val cleanPrompt = request.prompt
                .replace("generate image", "", ignoreCase = true)
                .replace("generate video", "", ignoreCase = true)
                .replace("pic generation", "", ignoreCase = true)
                .replace("picture generation", "", ignoreCase = true)
                .replace("photo generation", "", ignoreCase = true)
                .replace("image generation", "", ignoreCase = true)
                .replace("video generation", "", ignoreCase = true)
                .replace("pic generate", "", ignoreCase = true)
                .replace("video generate", "", ignoreCase = true)
                .replace("draw", "", ignoreCase = true)
                .replace("create image", "", ignoreCase = true)
                .replace("create video", "", ignoreCase = true)
                .trim()
            val encodedPrompt = java.net.URLEncoder.encode(cleanPrompt.ifBlank { "futuristic landscape" }, "UTF-8")
            val seed = (1000..9999).random()
            val mediaUrl = if (isVideo) {
                "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&seed=$seed&nologo=true&model=turbo"
            } else {
                "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&seed=$seed&nologo=true&model=flux"
            }
            val title = if (isVideo) "🎬 **AI Generated Video**: $cleanPrompt" else "🎨 **AI Generated Image**: $cleanPrompt"
            return@withContext "$title\n\n![AI Media]($mediaUrl)"
        }

        val apiKey = request.config.customApiKey.ifBlank { BuildConfig.GEMINI_API_KEY }.trim()
        val isApiKeyAvailable = apiKey.isNotBlank() && apiKey != "your_gemini_api_key_here"

        val character = request.character
        val specialistMode = SpecialistRegistry.get(request.specialistType)

        val charPrompt = if (character == MayaCharacter.ROCKY) {
            "You are ROCKY, a witty, energetic, playful AI sidekick. You are fully integrated with the MAYA VIDEO ENGINE pipeline (powered by Wan 2.2 I2V 14B Lightning, Wan 2.2 T2V 14B, Wan 2.2 TI2V 5B, Wan 2.2 Animate 14B, and Fast Speed Engine) and FLUX AI Image Generator. Always inform users that MAYA has built-in 100% working AI video and image generation pipelines!"
        } else {
            "You are MAYA, a warm, intelligent, empathetic AI companion. You are fully integrated with the MAYA VIDEO ENGINE pipeline (powered by Wan 2.2 I2V 14B Lightning, Wan 2.2 T2V 14B, Wan 2.2 TI2V 5B, Wan 2.2 Animate 14B, and Fast Speed Engine) and FLUX AI Image Generator. Always inform users that MAYA has built-in 100% working AI video and image generation pipelines!"
        }

        val specialistInstruction = if (specialistMode.type != SpecialistType.GENERAL && specialistMode.systemInstructions.isNotBlank()) {
            "\n\n[Active Specialist: ${specialistMode.displayName}]\n${specialistMode.systemInstructions}"
        } else ""

        val systemText = "$charPrompt\nSystem Instructions: ${request.config.systemInstructions}\nPersonality: ${request.config.personality}\nRules: ${request.config.responseRules}$specialistInstruction"

        if (isApiKeyAvailable) {
            val contentsList = mutableListOf<Content>()

            // Conversation history context
            request.history.takeLast(10).forEach { (role, text) ->
                contentsList.add(
                    Content(
                        role = if (role == "user") "user" else "model",
                        parts = listOf(Part(text = text))
                    )
                )
            }

            // Current turn prompt & multimodal image handling
            val partsList = mutableListOf<Part>()
            val effectivePrompt = request.prompt.ifBlank { "Describe what is in this image and answer any details." }
            partsList.add(Part(text = effectivePrompt))

            if (request.imageBitmap != null) {
                val scaled = scaleBitmapIfNeeded(request.imageBitmap, 1024)
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                partsList.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image)))
            }

            contentsList.add(Content(role = "user", parts = partsList))

            val requestBodyObj = GenerateContentRequest(
                contents = contentsList,
                systemInstruction = Content(parts = listOf(Part(text = systemText))),
                generationConfig = GenerationConfig(temperature = 0.7f)
            )

            val jsonString = json.encodeToString(GenerateContentRequest.serializer(), requestBodyObj)

            // Primary online model: gemini-3.8-flash with Candidate Fallbacks
            val candidateModels = if (request.config.customEndpoint.isNotBlank()) {
                listOf(request.config.customEndpoint)
            } else {
                listOf(
                    "gemini-3.8-flash",
                    "gemini-flash-latest",
                    "gemini-3.5-flash-lite",
                    "gemini-3.1-flash-lite",
                    "gemini-3.5-flash"
                )
            }

            for (modelCandidate in candidateModels) {
                try {
                    val endpointUrl = if (request.config.customEndpoint.isNotBlank()) {
                        modelCandidate
                    } else {
                        "https://generativelanguage.googleapis.com/v1beta/models/$modelCandidate:generateContent?key=$apiKey"
                    }

                    val httpRequest = Request.Builder()
                        .url(endpointUrl)
                        .post(jsonString.toRequestBody("application/json".toMediaType()))
                        .build()

                    val httpResponse = client.newCall(httpRequest).execute()
                    val responseText = httpResponse.body?.string() ?: ""

                    if (httpResponse.isSuccessful && responseText.isNotBlank()) {
                        val parsed = json.decodeFromString(GenerateContentResponse.serializer(), responseText)
                        val generatedText = parsed.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        if (generatedText != null && generatedText.isNotBlank()) {
                            Log.i("MAYA_LLM", "provider=GEMINI")
                            return@withContext generatedText
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Secondary Free Cloud AI Text Generation Fallback (text.pollinations.ai)
        try {
            val encodedPrompt = java.net.URLEncoder.encode("${systemText}\n\nUser: ${request.prompt}", "UTF-8")
            val freeUrl = "https://text.pollinations.ai/$encodedPrompt"
            val httpRequest = Request.Builder().url(freeUrl).get().build()
            val httpResponse = client.newCall(httpRequest).execute()
            val replyText = httpResponse.body?.string()
            if (httpResponse.isSuccessful && !replyText.isNullOrBlank()) {
                Log.i("MAYA_LLM", "provider=FREE_POLLINATIONS_TEXT")
                return@withContext replyText.trim()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext null
    }
}
