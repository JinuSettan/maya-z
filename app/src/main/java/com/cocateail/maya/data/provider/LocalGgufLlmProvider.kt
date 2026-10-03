package com.cocateail.maya.data.provider

import android.content.Context
import android.util.Log
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.data.native.LlamaBridge
import com.cocateail.maya.data.specialist.SpecialistRegistry
import com.cocateail.maya.data.specialist.SpecialistType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalGgufLlmProvider(
    private val context: Context? = null
) : LlmProvider {

    override val providerId: String = "local_gguf_provider"
    override val providerDisplayName: String = "Local GGUF Qwen2.5 Model (Native llama.cpp)"

    private var activeLlamaBridge: LlamaBridge? = null

    companion object {
        private const val TAG = "LocalGgufLlmProvider"
        private const val DEFAULT_MODEL_NAME = "qwen2.5-0.5b-instruct-q4_0.gguf"
        private const val GGUF_MAGIC = 0x46554747 // 'G''G''U''F' magic bytes in little endian
    }

    /**
     * Target local model file location in internal app storage (`/data/user/0/com.aistudio.mayaz.aichat/files/models/qwen2.5-0.5b-instruct-q4_0.gguf`).
     */
    fun getModelFile(): File? {
        if (context == null) return null
        return com.cocateail.maya.data.model.ModelDownloadManager.getModelFile(context)
    }

    /**
     * Inspects local GGUF file header to confirm binary integrity and magic bytes without loading model into RAM.
     */
    fun validateGgufHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 32) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(8)
                raf.readFully(buffer)
                val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                val magic = byteBuffer.int
                val version = byteBuffer.int
                magic == GGUF_MAGIC && (version == 2 || version == 3)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating GGUF header for ${file.absolutePath}", e)
            false
        }
    }

    override suspend fun generateResponse(request: LlmRequest): String? = withContext(Dispatchers.IO) {
        val modelFile = getModelFile()

        // 1. Verify model file exists and passes SHA-256
        if (modelFile == null || !modelFile.exists()) {
            Log.d(TAG, "Local GGUF model file absent at ${modelFile?.absolutePath}. Skipping local GGUF provider.")
            return@withContext null
        }

        if (context != null && !com.cocateail.maya.data.model.ModelDownloadManager.isModelReady(context)) {
            Log.w(TAG, "Model SHA-256 verification failed or incomplete at ${modelFile.absolutePath}.")
            return@withContext null
        }

        // 2. Validate GGUF header
        if (!validateGgufHeader(modelFile)) {
            Log.w(TAG, "GGUF header validation failed at ${modelFile.absolutePath}.")
            return@withContext null
        }

        // 3. Initialize or reuse LlamaBridge native context
        var bridge = activeLlamaBridge
        if (bridge == null || bridge.modelPath != modelFile.absolutePath) {
            bridge?.close()
            bridge = LlamaBridge.create(modelFile.absolutePath, nThreads = 4, nCtx = 2048)
            activeLlamaBridge = bridge
        }

        if (bridge == null) {
            Log.w(TAG, "LlamaBridge native context initialization failed or native library unavailable.")
            return@withContext null
        }

        // 4. Format prompt into Qwen ChatML format
        val character = request.character
        val specialistMode = SpecialistRegistry.get(request.specialistType)

        val charPrompt = if (character == MayaCharacter.ROCKY) {
            "You are ROCKY, a witty, energetic AI sidekick."
        } else {
            "You are MAYA, a warm, intelligent personal AI companion."
        }

        val specialistTag = if (specialistMode.type != SpecialistType.GENERAL) {
            "\n[Active Specialist: ${specialistMode.displayName}]\n${specialistMode.systemInstructions}"
        } else ""

        val systemPrompt = "$charPrompt$specialistTag\n${request.config.systemInstructions}"

        // Build multi-turn context in ChatML
        val chatMlBuilder = StringBuilder()
        chatMlBuilder.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")

        request.history.takeLast(6).forEach { (role, content) ->
            val roleTag = if (role == "user") "user" else "assistant"
            chatMlBuilder.append("<|im_start|>").append(roleTag).append("\n").append(content).append("<|im_end|>\n")
        }

        chatMlBuilder.append("<|im_start|>user\n").append(request.prompt).append("<|im_end|>\n<|im_start|>assistant\n")

        val formattedPrompt = chatMlBuilder.toString()

        // 5. Execute native token generation off main thread
        Log.i("MAYA_LLM", "provider=LOCAL_QWEN")
        val startTime = System.currentTimeMillis()

        val response = bridge.generate(
            prompt = formattedPrompt,
            maxTokens = 512,
            temperature = 0.7f,
            topP = 0.9f
        )

        val duration = System.currentTimeMillis() - startTime

        if (response != null && response.isNotBlank()) {
            val approxTokens = response.length / 4
            val tokensPerSec = if (duration > 0) (approxTokens * 1000.0) / duration else 0.0
            Log.i("MAYA_LLM", "provider=LOCAL_QWEN, gen_time_ms=$duration, approx_tokens=$approxTokens, speed=${String.format("%.2f", tokensPerSec)} t/s")
            return@withContext response.trim()
        }

        return@withContext null
    }

    /**
     * Safely releases native model RAM resources.
     */
    fun releaseNativeResources() {
        activeLlamaBridge?.close()
        activeLlamaBridge = null
    }
}
