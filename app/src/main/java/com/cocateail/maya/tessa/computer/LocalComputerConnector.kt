package com.cocateail.maya.tessa.computer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

interface LocalComputerConnector {
    fun isPaired(): Boolean
    fun isConnected(): Boolean
    fun getPairedComputer(): PairedComputerInfo?

    suspend fun pairComputer(
        computerId: String,
        deviceName: String,
        pairingToken: String,
        hostAddress: String,
        port: Int = 8990
    ): Result<PairedComputerInfo>

    suspend fun disconnect()
    suspend fun revokePairing()
    suspend fun reconnect(): Result<Boolean>
    suspend fun discoverCapabilities(): Result<List<ComputerCapability>>
    suspend fun executeRequest(request: ComputerAgentRequest): ComputerAgentResponse
}

class RealLocalComputerConnector(
    private val context: Context? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : LocalComputerConnector {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context?.getSharedPreferences(
        "tessa_paired_computer_prefs",
        Context.MODE_PRIVATE
    )

    private var activeComputer: PairedComputerInfo? = null

    init {
        loadPersistedComputer()
    }

    private fun loadPersistedComputer() {
        val raw = prefs?.getString("paired_computer_json", null) ?: return
        activeComputer = runCatching {
            json.decodeFromString<PairedComputerInfo>(raw)
        }.getOrNull()
    }

    private fun persistComputer(info: PairedComputerInfo?) {
        activeComputer = info
        val editor = prefs?.edit()
        if (info == null) {
            editor?.remove("paired_computer_json")
        } else {
            editor?.putString("paired_computer_json", json.encodeToString(info))
        }
        editor?.apply()
    }

    override fun isPaired(): Boolean =
        activeComputer != null &&
            activeComputer?.connectionStatus != ComputerPairingStatus.REVOKED

    override fun isConnected(): Boolean =
        activeComputer != null &&
            activeComputer?.connectionStatus == ComputerPairingStatus.CONNECTED

    override fun getPairedComputer(): PairedComputerInfo? = activeComputer

    override suspend fun pairComputer(
        computerId: String,
        deviceName: String,
        pairingToken: String,
        hostAddress: String,
        port: Int
    ): Result<PairedComputerInfo> = withContext(Dispatchers.IO) {
        if (computerId.isBlank() || pairingToken.isBlank() || hostAddress.isBlank()) {
            return@withContext Result.failure(
                ComputerException(
                    "INVALID_PAIRING_DATA",
                    "Computer ID, pairing token, and host address are required."
                )
            )
        }

        if (port !in 1..65535) {
            return@withContext Result.failure(
                ComputerException("INVALID_PORT", "Port must be between 1 and 65535.")
            )
        }

        try {
            val url = "http://$hostAddress:$port/api/tessa/pair"
            val payload = mapOf(
                "computerId" to computerId,
                "deviceName" to deviceName,
                "pairingToken" to pairingToken,
                "timestamp" to System.currentTimeMillis().toString()
            )
            val jsonPayload = json.encodeToString(payload)
            val signature = ComputerAuthUtils.sign(jsonPayload, pairingToken)

            val httpRequest = Request.Builder()
                .url(url)
                .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                .addHeader("X-Tessa-Signature", signature)
                .build()

            val sessionToken = httpClient.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        ComputerException(
                            "PAIRING_FAILED",
                            "Companion server responded with HTTP ${response.code}"
                        )
                    )
                }

                val parsed = runCatching {
                    json.parseToJsonElement(body).jsonObject
                }.getOrElse {
                    return@withContext Result.failure(
                        ComputerException(
                            "INVALID_PAIR_RESPONSE",
                            "The computer server returned invalid JSON."
                        )
                    )
                }

                val succeeded = parsed["success"]?.jsonPrimitive?.booleanOrNull == true
                val token = parsed["sessionToken"]?.jsonPrimitive?.contentOrNull

                if (!succeeded || token.isNullOrBlank()) {
                    return@withContext Result.failure(
                        ComputerException(
                            "INVALID_PAIR_RESPONSE",
                            "The computer server did not return a valid session token."
                        )
                    )
                }

                token
            }

            val capsResult = discoverCapabilitiesInternal(
                hostAddress,
                port,
                sessionToken,
                pairingToken
            )

            val caps = capsResult.getOrElse { error ->
                // Do not claim a successful pairing when the server's actual
                // capability list could not be read.
                runCatching {
                    val revokePayload = "{}"
                    val revokeRequest = Request.Builder()
                        .url("http://$hostAddress:$port/api/tessa/revoke")
                        .post(revokePayload.toRequestBody("application/json".toMediaType()))
                        .addHeader("X-Tessa-Session", sessionToken)
                        .addHeader(
                            "X-Tessa-Signature",
                            ComputerAuthUtils.sign(revokePayload, pairingToken)
                        )
                        .build()
                    httpClient.newCall(revokeRequest).execute().close()
                }
                return@withContext Result.failure(
                    ComputerException(
                        "CAPABILITY_DISCOVERY_FAILED",
                        "Pairing was not saved because capabilities could not be verified: ${error.message}",
                        error
                    )
                )
            }

            val paired = PairedComputerInfo(
                computerId = computerId,
                deviceName = deviceName.ifBlank { "Personal Computer" },
                pairingToken = pairingToken,
                sessionToken = sessionToken,
                hostAddress = hostAddress,
                port = port,
                connectionStatus = ComputerPairingStatus.CONNECTED,
                lastSeenTimestamp = System.currentTimeMillis(),
                capabilities = caps,
                workspaceRoot = "/workspace"
            )

            persistComputer(paired)
            Result.success(paired)
        } catch (e: Exception) {
            Result.failure(
                ComputerException(
                    "CONNECTION_REFUSED",
                    "Could not connect to computer companion service: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val current = activeComputer ?: return@withContext
        persistComputer(current.copy(connectionStatus = ComputerPairingStatus.DISCONNECTED))
    }

    override suspend fun revokePairing() = withContext(Dispatchers.IO) {
        val current = activeComputer ?: return@withContext
        val url = "http://${current.hostAddress}:${current.port}/api/tessa/revoke"
        val payload = "{}"
        val signature = ComputerAuthUtils.sign(payload, current.pairingToken)

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .addHeader("X-Tessa-Session", current.sessionToken)
            .addHeader("X-Tessa-Signature", signature)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ComputerException(
                    "REVOKE_FAILED",
                    "Server rejected pairing revocation: HTTP ${response.code}. Local pairing was kept."
                )
            }

            val body = response.body?.string().orEmpty()
            val confirmed = runCatching {
                json.parseToJsonElement(body).jsonObject["success"]
                    ?.jsonPrimitive?.booleanOrNull == true
            }.getOrDefault(false)

            if (!confirmed) {
                throw ComputerException(
                    "REVOKE_NOT_CONFIRMED",
                    "The server did not confirm revocation. Local pairing was kept."
                )
            }
        }

        persistComputer(null)
    }

    override suspend fun reconnect(): Result<Boolean> = withContext(Dispatchers.IO) {
        val current = activeComputer ?: return@withContext Result.failure(
            ComputerException("NOT_PAIRED", "No paired computer configured.")
        )

        try {
            val url = "http://${current.hostAddress}:${current.port}/api/tessa/health"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-Tessa-Session", current.sessionToken)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    persistComputer(
                        current.copy(
                            connectionStatus = ComputerPairingStatus.CONNECTED,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                    )
                    Result.success(true)
                } else {
                    persistComputer(
                        current.copy(connectionStatus = ComputerPairingStatus.DISCONNECTED)
                    )
                    Result.failure(
                        ComputerException(
                            "HEALTH_CHECK_FAILED",
                            "Computer responded with HTTP ${response.code}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            persistComputer(current.copy(connectionStatus = ComputerPairingStatus.DISCONNECTED))
            Result.failure(
                ComputerException(
                    "OFFLINE",
                    "Computer companion is currently unreachable: ${e.message}",
                    e
                )
            )
        }
    }

    override suspend fun discoverCapabilities(): Result<List<ComputerCapability>> =
        withContext(Dispatchers.IO) {
            val current = activeComputer ?: return@withContext Result.failure(
                ComputerException("NOT_PAIRED", "No paired computer configured.")
            )
            discoverCapabilitiesInternal(
                current.hostAddress,
                current.port,
                current.sessionToken,
                current.pairingToken
            )
        }

    private fun discoverCapabilitiesInternal(
        host: String,
        port: Int,
        sessionToken: String,
        @Suppress("UNUSED_PARAMETER") pairingToken: String
    ): Result<List<ComputerCapability>> {
        return try {
            val url = "http://$host:$port/api/tessa/capabilities"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-Tessa-Session", sessionToken)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(
                        ComputerException(
                            "CAPABILITY_FETCH_FAILED",
                            "Capability endpoint returned HTTP ${response.code}"
                        )
                    )
                }

                val root = runCatching {
                    json.parseToJsonElement(body).jsonObject
                }.getOrElse {
                    return Result.failure(
                        ComputerException(
                            "INVALID_CAPABILITY_RESPONSE",
                            "The computer server returned invalid capability JSON.",
                            it
                        )
                    )
                }

                val capabilityArray = runCatching {
                    root["capabilities"]?.jsonArray
                }.getOrNull() ?: return Result.failure(
                    ComputerException(
                        "INVALID_CAPABILITY_RESPONSE",
                        "The server response did not contain a capabilities array."
                    )
                )

                val capabilities = capabilityArray.mapNotNull { element ->
                    element.jsonPrimitive.contentOrNull?.let(ComputerCapability::fromString)
                }

                Result.success(capabilities)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun executeRequest(
        request: ComputerAgentRequest
    ): ComputerAgentResponse = withContext(Dispatchers.IO) {
        val current = activeComputer
        if (current == null || current.connectionStatus != ComputerPairingStatus.CONNECTED) {
            return@withContext ComputerAgentResponse.unavailable(
                "No authorized computer is currently connected. Please pair or connect your computer in TESSA settings."
            )
        }

        try {
            val url = "http://${current.hostAddress}:${current.port}/api/tessa/action"
            val jsonPayload = json.encodeToString(request)
            val signature = ComputerAuthUtils.sign(jsonPayload, current.pairingToken)

            val httpRequest = Request.Builder()
                .url(url)
                .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                .addHeader("X-Tessa-Session", current.sessionToken)
                .addHeader("X-Tessa-Signature", signature)
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return@withContext ComputerAgentResponse(
                        success = false,
                        statusCode = response.code,
                        outputSummary = "Computer returned HTTP ${response.code}: $body",
                        errorCode = "DESKTOP_ACTION_FAILED"
                    )
                }

                val decoded = runCatching {
                    val root = json.parseToJsonElement(body).jsonObject
                    val success = root["success"]?.jsonPrimitive?.booleanOrNull
                        ?: throw IllegalArgumentException("Response is missing a boolean success field.")
                    val outputSummary = root["outputSummary"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Response is missing outputSummary.")

                    val data = root["data"]?.let { dataElement ->
                        runCatching { dataElement.jsonObject }.getOrNull()
                            ?.mapValues { (_, value) ->
                                runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
                                    ?: value.toString()
                            }
                            .orEmpty()
                    }.orEmpty()

                    val artifacts = root["artifacts"]?.let { element ->
                        runCatching {
                            element.jsonArray.map { item ->
                                item.jsonPrimitive.contentOrNull ?: item.toString()
                            }
                        }.getOrNull()
                    }.orEmpty()

                    ComputerAgentResponse(
                        success = success,
                        statusCode = root["statusCode"]?.jsonPrimitive?.contentOrNull
                            ?.toIntOrNull() ?: response.code,
                        outputSummary = outputSummary,
                        data = data,
                        errorCode = root["errorCode"]?.jsonPrimitive?.contentOrNull,
                        artifacts = artifacts
                    )
                }.getOrElse { error ->
                    return@withContext ComputerAgentResponse(
                        success = false,
                        statusCode = 502,
                        outputSummary = "Computer returned an unreadable response. The action result could not be verified.",
                        errorCode = "INVALID_ACTION_RESPONSE"
                    )
                }

                persistComputer(current.copy(lastSeenTimestamp = System.currentTimeMillis()))
                decoded
            }
        } catch (e: Exception) {
            ComputerAgentResponse.unavailable(
                "Failed to communicate with paired computer companion: ${e.message}"
            )
        }
    }
}
