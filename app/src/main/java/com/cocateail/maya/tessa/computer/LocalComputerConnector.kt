package com.cocateail.maya.tessa.computer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
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
    private val prefs = context?.getSharedPreferences("tessa_paired_computer_prefs", Context.MODE_PRIVATE)

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
        activeComputer != null && activeComputer?.connectionStatus != ComputerPairingStatus.REVOKED

    override fun isConnected(): Boolean =
        activeComputer != null && activeComputer?.connectionStatus == ComputerPairingStatus.CONNECTED

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
                ComputerException("INVALID_PAIRING_DATA", "Computer ID, pairing token, and host address are required.")
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

            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    ComputerException("PAIRING_FAILED", "Companion server responded with HTTP ${response.code}")
                )
            }

            val body = response.body?.string().orEmpty()
            val sessionToken = runCatching {
                val parsed = json.decodeFromString<Map<String, String>>(body)
                parsed["sessionToken"] ?: "sess-${UUID.randomUUID().toString().take(16)}"
            }.getOrDefault("sess-${UUID.randomUUID().toString().take(16)}")

            val caps = discoverCapabilitiesInternal(hostAddress, port, sessionToken, pairingToken)
                .getOrDefault(listOf(
                    ComputerCapability.FILE_READ,
                    ComputerCapability.FILE_WRITE,
                    ComputerCapability.TERMINAL,
                    ComputerCapability.BROWSER,
                    ComputerCapability.BUILD
                ))

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
            // Never fake connection success
            Result.failure(
                ComputerException("CONNECTION_REFUSED", "Could not connect to computer companion service: ${e.message}", e)
            )
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val current = activeComputer ?: return@withContext
        val updated = current.copy(connectionStatus = ComputerPairingStatus.DISCONNECTED)
        persistComputer(updated)
    }

    override suspend fun revokePairing() = withContext(Dispatchers.IO) {
        val current = activeComputer ?: return@withContext
        // Notify computer of revocation if reachable
        runCatching {
            val url = "http://${current.hostAddress}:${current.port}/api/tessa/revoke"
            val req = Request.Builder()
                .url(url)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .addHeader("X-Tessa-Session", current.sessionToken)
                .build()
            httpClient.newCall(req).execute()
        }
        persistComputer(null)
    }

    override suspend fun reconnect(): Result<Boolean> = withContext(Dispatchers.IO) {
        val current = activeComputer ?: return@withContext Result.failure(
            ComputerException("NOT_PAIRED", "No paired computer configured.")
        )

        try {
            val url = "http://${current.hostAddress}:${current.port}/api/tessa/health"
            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-Tessa-Session", current.sessionToken)
                .build()

            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val updated = current.copy(
                    connectionStatus = ComputerPairingStatus.CONNECTED,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
                persistComputer(updated)
                Result.success(true)
            } else {
                val updated = current.copy(connectionStatus = ComputerPairingStatus.DISCONNECTED)
                persistComputer(updated)
                Result.failure(ComputerException("HEALTH_CHECK_FAILED", "Computer responded with HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            val updated = current.copy(connectionStatus = ComputerPairingStatus.DISCONNECTED)
            persistComputer(updated)
            Result.failure(ComputerException("OFFLINE", "Computer companion is currently unreachable: ${e.message}", e))
        }
    }

    override suspend fun discoverCapabilities(): Result<List<ComputerCapability>> = withContext(Dispatchers.IO) {
        val current = activeComputer ?: return@withContext Result.failure(
            ComputerException("NOT_PAIRED", "No paired computer configured.")
        )
        discoverCapabilitiesInternal(current.hostAddress, current.port, current.sessionToken, current.pairingToken)
    }

    private fun discoverCapabilitiesInternal(
        host: String,
        port: Int,
        sessionToken: String,
        pairingToken: String
    ): Result<List<ComputerCapability>> {
        return try {
            val url = "http://$host:$port/api/tessa/capabilities"
            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-Tessa-Session", sessionToken)
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return Result.failure(ComputerException("CAPABILITY_FETCH_FAILED", "HTTP ${resp.code}"))
            }
            val body = resp.body?.string().orEmpty()
            val parsedList = runCatching {
                val map = json.decodeFromString<Map<String, List<String>>>(body)
                val rawCaps = map["capabilities"].orEmpty()
                rawCaps.mapNotNull { ComputerCapability.fromString(it) }
            }.getOrDefault(emptyList())

            Result.success(parsedList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun executeRequest(request: ComputerAgentRequest): ComputerAgentResponse = withContext(Dispatchers.IO) {
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

            val response = httpClient.newCall(httpRequest).execute()
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
                json.decodeFromString<ComputerAgentResponse>(body)
            }.getOrElse {
                ComputerAgentResponse(
                    success = true,
                    statusCode = 200,
                    outputSummary = body.ifBlank { "Action executed successfully." }
                )
            }

            // Update last seen
            val refreshed = current.copy(lastSeenTimestamp = System.currentTimeMillis())
            persistComputer(refreshed)

            decoded
        } catch (e: Exception) {
            ComputerAgentResponse.unavailable("Failed to communicate with paired computer companion: ${e.message}")
        }
    }
}
