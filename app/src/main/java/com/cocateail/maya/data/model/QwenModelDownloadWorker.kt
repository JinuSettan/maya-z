package com.cocateail.maya.data.model

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class QwenModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MAYA_MODEL"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        Log.d(TAG, "download started")

        // 1. Check if model is already downloaded & verified
        if (ModelDownloadManager.isModelReady(context)) {
            Log.d(TAG, "model ready (already verified)")
            return@withContext Result.success()
        }

        val partFile = ModelDownloadManager.getPartFile(context)
        val finalFile = ModelDownloadManager.getModelFile(context)

        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        // If part file exceeds expected size, delete and restart
        if (existingBytes > ModelDownloadManager.EXPECTED_SIZE_BYTES) {
            Log.w(TAG, "Part file size exceeds expected model size. Deleting invalid part file.")
            partFile.delete()
            existingBytes = 0L
        }

        val requestBuilder = Request.Builder()
            .url(ModelDownloadManager.DOWNLOAD_URL)

        if (existingBytes > 0) {
            Log.d(TAG, "download resumed from byte $existingBytes")
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val request = requestBuilder.build()

        try {
            val response = client.newCall(request).execute()
            val responseCode = response.code

            // Handle HTTP 416 (Range Not Satisfiable)
            if (responseCode == 416) {
                Log.w(TAG, "HTTP 416 Range Not Satisfiable. Resetting part file and restarting download.")
                partFile.delete()
                response.close()
                return@withContext Result.retry()
            }

            if (!response.isSuccessful && responseCode != 206) {
                Log.w(TAG, "Download HTTP request failed with code $responseCode")
                response.close()
                return@withContext Result.retry()
            }

            val body = response.body
            if (body == null) {
                Log.w(TAG, "Download HTTP response body was null")
                response.close()
                return@withContext Result.retry()
            }

            // HTTP 206 -> Append mode; HTTP 200 -> Restart from byte 0
            val appendMode = (responseCode == 206 && existingBytes > 0)
            if (responseCode == 200 && existingBytes > 0) {
                Log.d(TAG, "Server responded with HTTP 200 (ignored Range header). Restarting file from byte 0.")
            }

            val inputStream: InputStream = body.byteStream()
            val outputStream = FileOutputStream(partFile, appendMode)

            try {
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var bytesWrittenSinceUpdate = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isStopped) {
                        Log.d(TAG, "Download worker cancelled by WorkManager.")
                        return@withContext Result.retry()
                    }
                    outputStream.write(buffer, 0, bytesRead)
                    bytesWrittenSinceUpdate += bytesRead

                    if (bytesWrittenSinceUpdate >= 256 * 1024) {
                        val currentTotal = partFile.length()
                        val percent = ((currentTotal * 100) / ModelDownloadManager.EXPECTED_SIZE_BYTES).toInt().coerceIn(0, 99)
                        ModelDownloadManager.updateProgress(currentTotal, percent)
                        bytesWrittenSinceUpdate = 0L
                    }
                }
                outputStream.flush()
            } finally {
                try { outputStream.close() } catch (_: Exception) {}
                try { inputStream.close() } catch (_: Exception) {}
                response.close()
            }

            Log.d(TAG, "download completed. Verifying file size and SHA-256...")

            // 2. Strict file size verification (428,730,208 bytes)
            val actualSize = partFile.length()
            if (actualSize != ModelDownloadManager.EXPECTED_SIZE_BYTES) {
                Log.e(TAG, "File size mismatch (Got: $actualSize, Expected: ${ModelDownloadManager.EXPECTED_SIZE_BYTES})")
                partFile.delete()
                Log.d(TAG, "download retry")
                return@withContext Result.retry()
            }

            // 3. Strict streaming SHA-256 verification
            val sha256 = ModelDownloadManager.calculateSha256(partFile)
            if (!sha256.equals(ModelDownloadManager.EXPECTED_SHA256, ignoreCase = true)) {
                Log.e(TAG, "sha256 verification failed (Got: $sha256, Expected: ${ModelDownloadManager.EXPECTED_SHA256})")
                partFile.delete()
                Log.d(TAG, "download retry")
                return@withContext Result.retry()
            }

            Log.d(TAG, "sha256 verified")

            // 4. Safe atomic activation & rename
            val activated = if (partFile.renameTo(finalFile)) {
                true
            } else {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
                true
            }

            if (activated && finalFile.exists() && finalFile.length() == ModelDownloadManager.EXPECTED_SIZE_BYTES) {
                Log.d(TAG, "model ready")
                ModelDownloadManager.markStatusReady()
                return@withContext Result.success()
            } else {
                Log.e(TAG, "Model activation rename failed.")
                ModelDownloadManager.markStatusFailed("Activation failed")
                Log.d(TAG, "download retry")
                return@withContext Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during model download worker", e)
            Log.d(TAG, "download retry")
            return@withContext Result.retry()
        }
    }
}
