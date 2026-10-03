package com.cocateail.maya.data.native

import android.util.Log

/**
 * Native JNI Bridge for llama.cpp execution on Android devices.
 * Interfaces with libllama.so / libllama_jni.so for GGUF model execution (Qwen2.5 / Q4_0).
 */
class LlamaBridge private constructor(
    private var nativeContextPtr: Long,
    val modelPath: String
) {
    companion object {
        private const val TAG = "LlamaBridge"
        private var isNativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("llama_jni")
                isNativeLibraryLoaded = true
                Log.i(TAG, "Successfully loaded native libllama_jni library.")
            } catch (e: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("llama")
                    isNativeLibraryLoaded = true
                    Log.i(TAG, "Successfully loaded native libllama library.")
                } catch (e2: UnsatisfiedLinkError) {
                    Log.w(TAG, "Native library not present in current build container: ${e2.message}")
                    isNativeLibraryLoaded = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native llama library", e)
                isNativeLibraryLoaded = false
            }
        }

        fun isNativeAvailable(): Boolean = isNativeLibraryLoaded

        /**
         * Safely attempts to initialize a llama.cpp context for the specified GGUF model file path.
         */
        fun create(
            modelPath: String,
            nThreads: Int = 4,
            nCtx: Int = 2048
        ): LlamaBridge? {
            if (!isNativeAvailable()) {
                Log.w(TAG, "Cannot create LlamaBridge: Native library libllama is not loaded.")
                return null
            }

            return try {
                val ptr = nativeInitModel(modelPath, nThreads, nCtx)
                if (ptr != 0L) {
                    LlamaBridge(ptr, modelPath)
                } else {
                    Log.e(TAG, "nativeInitModel returned 0 pointer for $modelPath")
                    null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Exception during nativeInitModel for $modelPath", e)
                null
            }
        }

        @JvmStatic
        private external fun nativeInitModel(modelPath: String, nThreads: Int, nCtx: Int): Long

        @JvmStatic
        private external fun nativeTokenize(ptr: Long, text: String, addBos: Boolean): IntArray

        @JvmStatic
        private external fun nativeEval(ptr: Long, tokens: IntArray): Boolean

        @JvmStatic
        private external fun nativeSampleToken(ptr: Long, temperature: Float, topP: Float): Int

        @JvmStatic
        private external fun nativeTokenToPiece(ptr: Long, token: Int): String

        @JvmStatic
        private external fun nativeFreeModel(ptr: Long)
    }

    /**
     * Generates text given a formatted ChatML prompt string.
     */
    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        stopTokenIds: Set<Int> = setOf(151643, 151645) // Qwen2.5 end-of-text & end-of-turn token IDs
    ): String? {
        if (nativeContextPtr == 0L) return null

        return try {
            val tokens = nativeTokenize(nativeContextPtr, prompt, true)
            if (tokens.isEmpty()) return null

            val evalSuccess = nativeEval(nativeContextPtr, tokens)
            if (!evalSuccess) return null

            val sb = StringBuilder()
            var currentTokenCount = 0

            while (currentTokenCount < maxTokens) {
                val nextToken = nativeSampleToken(nativeContextPtr, temperature, topP)
                if (nextToken <= 0 || stopTokenIds.contains(nextToken)) {
                    break
                }

                val piece = nativeTokenToPiece(nativeContextPtr, nextToken)
                sb.append(piece)

                val evalNext = nativeEval(nativeContextPtr, intArrayOf(nextToken))
                if (!evalNext) break

                currentTokenCount++
            }

            sb.toString()
        } catch (e: Throwable) {
            Log.e(TAG, "Native token generation error", e)
            null
        }
    }

    /**
     * Frees native C++ model pointers and releases memory.
     */
    fun close() {
        if (nativeContextPtr != 0L) {
            try {
                nativeFreeModel(nativeContextPtr)
            } catch (e: Throwable) {
                Log.e(TAG, "Error freeing native llama model pointer", e)
            } finally {
                nativeContextPtr = 0L
            }
        }
    }

    protected fun finalize() {
        close()
    }
}
