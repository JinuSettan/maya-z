package com.cocateail.maya.tessa.computer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class ComputerCapability(val displayName: String) {
    FILE_READ("File Read"),
    FILE_WRITE("File Write"),
    FILE_CREATE("File Create"),
    FILE_DELETE("File Delete"),
    DIRECTORY_CREATE("Directory Create"),
    TERMINAL("Safe Terminal"),
    PROCESS_CONTROL("Process Control"),
    APP_LAUNCH("Application Launch"),
    SCREENSHOT("Screenshot"),
    BROWSER("Web Browser"),
    UNREAL_ENGINE("Unreal Engine"),
    GIT("Git Operations"),
    BUILD("Project Build"),
    TEST("Test Execution");

    companion object {
        fun fromString(value: String): ComputerCapability? =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

enum class ComputerPairingStatus {
    UNPAIRED,
    PAIRING,
    CONNECTED,
    DISCONNECTED,
    REVOKED
}

@Serializable
data class PairedComputerInfo(
    val computerId: String,
    val deviceName: String,
    val pairingToken: String,
    val sessionToken: String,
    val hostAddress: String,
    val port: Int = 8990,
    val connectionStatus: ComputerPairingStatus = ComputerPairingStatus.DISCONNECTED,
    val lastSeenTimestamp: Long = 0L,
    val capabilities: List<ComputerCapability> = emptyList(),
    val osInfo: String = "Unknown OS",
    val workspaceRoot: String = ""
)

@Serializable
data class ComputerAgentRequest(
    val deviceId: String,
    val sessionToken: String,
    val taskId: String,
    val action: String,
    val target: String = "",
    val permission: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val parameters: Map<String, String> = emptyMap()
)

@Serializable
data class ComputerAgentResponse(
    val success: Boolean,
    val statusCode: Int = 200,
    val outputSummary: String,
    val data: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val artifacts: List<String> = emptyList()
) {
    companion object {
        fun unavailable(reason: String, code: String = "TOOL_UNAVAILABLE"): ComputerAgentResponse =
            ComputerAgentResponse(
                success = false,
                statusCode = 503,
                outputSummary = "TOOL_UNAVAILABLE: $reason",
                errorCode = code
            )

        fun capabilityUnavailable(capability: ComputerCapability): ComputerAgentResponse =
            ComputerAgentResponse(
                success = false,
                statusCode = 404,
                outputSummary = "CAPABILITY_UNAVAILABLE: The paired computer does not support ${capability.name}.",
                errorCode = "CAPABILITY_UNAVAILABLE"
            )

        fun permissionDenied(reason: String): ComputerAgentResponse =
            ComputerAgentResponse(
                success = false,
                statusCode = 403,
                outputSummary = "PERMISSION_DENIED: $reason",
                errorCode = "PERMISSION_DENIED"
            )
    }
}

class ComputerException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : Exception("[$errorCode] $message", cause)

object ComputerAuthUtils {

    /**
     * Computes HMAC-SHA256 signature for authenticating payloads with the pairing token.
     */
    fun sign(payload: String, secretKey: String): String {
        return runCatching {
            val keySpec = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    /**
     * Constant-time comparison to prevent timing attacks.
     */
    fun verifySignature(payload: String, secretKey: String, expectedSignature: String): Boolean {
        val actual = sign(payload, secretKey)
        if (actual.isEmpty() || expectedSignature.isEmpty()) return false
        return MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expectedSignature.toByteArray(Charsets.UTF_8))
    }
}
