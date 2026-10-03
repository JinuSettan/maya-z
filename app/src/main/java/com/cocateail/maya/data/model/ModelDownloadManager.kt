package com.cocateail.maya.data.model

import android.content.Context
import android.util.Log
import androidx.work.*
import com.cocateail.maya.data.provider.LlmRequest
import com.cocateail.maya.data.provider.LocalGgufLlmProvider
import com.cocateail.maya.data.specialist.SpecialistType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object ModelDownloadManager {
    private const val TAG = "MAYA_MODEL"

    const val MODEL_FILENAME = "qwen2.5-0.5b-instruct-q4_0.gguf"
    const val PART_FILENAME = "qwen2.5-0.5b-instruct-q4_0.gguf.part"
    const val DOWNLOAD_URL = "https://github.com/JinuSettan/maya-z/releases/download/maya-model-v1/qwen2.5-0.5b-instruct-q4_0.gguf"
    const val EXPECTED_SHA256 = "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed"
    const val EXPECTED_SIZE_BYTES = 428730208L
    const val WORK_NAME = "maya_qwen25_05b_model_download"

    enum class ModelStatus {
        NOT_AVAILABLE,
        DOWNLOADING,
        READY,
        FAILED
    }

    data class ModelDownloadInfo(
        val status: ModelStatus = ModelStatus.NOT_AVAILABLE,
        val progressPercent: Int = 0,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = EXPECTED_SIZE_BYTES,
        val statusMessage: String = "Not Downloaded"
    )

    private val _downloadInfo = MutableStateFlow(ModelDownloadInfo())
    val downloadInfo: StateFlow<ModelDownloadInfo> = _downloadInfo.asStateFlow()

    fun getModelsDirectory(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelFile(context: Context): File {
        return File(getModelsDirectory(context), MODEL_FILENAME)
    }

    fun getPartFile(context: Context): File {
        return File(getModelsDirectory(context), PART_FILENAME)
    }

    fun updateProgress(downloadedBytes: Long, progressPercent: Int) {
        _downloadInfo.value = ModelDownloadInfo(
            status = ModelStatus.DOWNLOADING,
            progressPercent = progressPercent.coerceIn(0, 99),
            downloadedBytes = downloadedBytes,
            totalBytes = EXPECTED_SIZE_BYTES,
            statusMessage = "Downloading Qwen2.5 GGUF..."
        )
    }

    fun markStatusFailed(msg: String = "Download Failed") {
        _downloadInfo.value = ModelDownloadInfo(
            status = ModelStatus.FAILED,
            progressPercent = 0,
            statusMessage = msg
        )
    }

    fun markStatusReady() {
        _downloadInfo.value = ModelDownloadInfo(
            status = ModelStatus.READY,
            progressPercent = 100,
            downloadedBytes = EXPECTED_SIZE_BYTES,
            totalBytes = EXPECTED_SIZE_BYTES,
            statusMessage = "Active / Ready for Offline AI"
        )
    }

    /**
     * Calculates SHA-256 checksum of a file using a streaming 64KB buffer.
     * Prevents loading large files into RAM.
     */
    fun calculateSha256(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            file.inputStream().use { inputStream ->
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating SHA-256 for ${file.name}", e)
            ""
        }
    }

    /**
     * Checks if the target model exists in context.filesDir/models/, matches exact byte size (428,730,208 bytes),
     * and passes streaming SHA-256 verification.
     */
    fun isModelReady(context: Context): Boolean {
        val modelFile = getModelFile(context)
        if (!modelFile.exists()) {
            val partFile = getPartFile(context)
            if (partFile.exists() && partFile.length() > 0) {
                val currentBytes = partFile.length()
                val percent = ((currentBytes * 100) / EXPECTED_SIZE_BYTES).toInt().coerceIn(0, 99)
                _downloadInfo.value = ModelDownloadInfo(
                    status = ModelStatus.DOWNLOADING,
                    progressPercent = percent,
                    downloadedBytes = currentBytes,
                    statusMessage = "Downloading Qwen2.5 GGUF..."
                )
            } else {
                if (_downloadInfo.value.status != ModelStatus.DOWNLOADING) {
                    _downloadInfo.value = ModelDownloadInfo(
                        status = ModelStatus.NOT_AVAILABLE,
                        statusMessage = "Not Downloaded"
                    )
                }
            }
            return false
        }

        if (modelFile.length() != EXPECTED_SIZE_BYTES) {
            Log.w(TAG, "Existing model size mismatch (Got: ${modelFile.length()}, Expected: $EXPECTED_SIZE_BYTES). Deleting corrupt file.")
            modelFile.delete()
            markStatusFailed("Model file corrupted")
            return false
        }

        val hash = calculateSha256(modelFile)
        val matches = hash.equals(EXPECTED_SHA256, ignoreCase = true)
        if (matches) {
            Log.d(TAG, "model ready (Size & SHA-256 verified)")
            markStatusReady()
        } else {
            Log.w(TAG, "Existing model SHA-256 mismatch (Got: $hash). Deleting corrupt file.")
            modelFile.delete()
            markStatusFailed("SHA-256 Verification Failed")
        }
        return matches
    }

    /**
     * Enqueues a silent WorkManager background task if the verified GGUF model is not yet present.
     */
    fun initAndEnqueueDownload(context: Context) {
        val modelFile = getModelFile(context)
        if (modelFile.exists()) {
            Log.d(TAG, "Checking existing model size and SHA-256...")
            if (isModelReady(context)) {
                Log.d(TAG, "model ready")
                return
            }
        }

        triggerManualDownload(context, ExistingWorkPolicy.KEEP)
    }

    /**
     * Explicit manual trigger for downloading or retrying offline model download.
     */
    fun triggerManualDownload(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        Log.d(TAG, "Enqueueing background download for $MODEL_FILENAME...")

        val partFile = getPartFile(context)
        val currentBytes = if (partFile.exists()) partFile.length() else 0L
        val percent = ((currentBytes * 100) / EXPECTED_SIZE_BYTES).toInt().coerceIn(0, 99)

        _downloadInfo.value = ModelDownloadInfo(
            status = ModelStatus.DOWNLOADING,
            progressPercent = percent,
            downloadedBytes = currentBytes,
            statusMessage = "Downloading Qwen2.5 GGUF..."
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val downloadWork = OneTimeWorkRequestBuilder<QwenModelDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            policy,
            downloadWork
        )
    }

    /**
     * Developer offline test function verifying file existence, size, SHA-256, and real llama.cpp execution.
     */
    suspend fun testOfflineInference(
        context: Context,
        prompt: String = "Hello Maya, are you working offline?"
    ): String = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting developer offline inference test with prompt: '$prompt'")
        if (!isModelReady(context)) {
            return@withContext "TEST FAILED: Model file absent or SHA-256 verification pending."
        }

        val provider = LocalGgufLlmProvider(context)
        val request = LlmRequest(
            prompt = prompt,
            character = MayaCharacter.MAYA,
            specialistType = SpecialistType.GENERAL,
            history = emptyList(),
            config = DeveloperConfig()
        )

        val startTime = System.currentTimeMillis()
        val response = provider.generateResponse(request)
        val duration = System.currentTimeMillis() - startTime

        if (!response.isNullOrBlank()) {
            Log.i(TAG, "Developer offline inference test SUCCESS ($duration ms): $response")
            "TEST PASSED ($duration ms):\n$response"
        } else {
            Log.e(TAG, "Developer offline inference test FAILED: Provider returned null.")
            "TEST FAILED: LocalGgufLlmProvider returned null response."
        }
    }
}
