package com.cocateail.maya.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.ModelDownloadManager
import com.cocateail.maya.data.specialist.SpecialistType
import com.cocateail.maya.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperPanelDialog(
    config: DeveloperConfig,
    onDismiss: () -> Unit,
    onSave: (DeveloperConfig) -> Unit
) {
    var systemInst by remember { mutableStateOf(config.systemInstructions) }
    var personality by remember { mutableStateOf(config.personality) }
    var responseRules by remember { mutableStateOf(config.responseRules) }
    var ttsEnabled by remember { mutableStateOf(config.ttsEnabled) }
    var ttsRate by remember { mutableStateOf(config.ttsRate) }
    var faceEnabled by remember { mutableStateOf(config.faceEnabled) }
    var customKey by remember { mutableStateOf(config.customApiKey) }
    var customEndpoint by remember { mutableStateOf(config.customEndpoint) }

    var autoDetectSpecialist by remember { mutableStateOf(config.autoDetectSpecialist) }
    var selectedSpecialist by remember { mutableStateOf(config.selectedSpecialist) }
    var maxFixAttempts by remember { mutableStateOf(config.maxFixAttempts) }

    var githubRepo by remember { mutableStateOf(config.githubRepository) }
    var githubBranch by remember { mutableStateOf(config.githubBranch) }
    var cloudRunUrl by remember { mutableStateOf(config.resolvedCloudRunUrl()) }
    var cloudRunKey by remember { mutableStateOf(config.resolvedCloudRunApiKey()) }
    var backendPolicy by remember { mutableStateOf(config.backendSelectionPolicy) }
    var preferredArtifact by remember { mutableStateOf(config.preferredArtifactType) }
    var tessaModelId by remember { mutableStateOf(config.tessaModelId.ifBlank { DeveloperConfig.DEFAULT_TESSA_MODEL_ID }) }
    var tessaApiBaseUrl by remember { mutableStateOf(config.tessaApiBaseUrl.ifBlank { DeveloperConfig.DEFAULT_TESSA_BASE_URL }) }
    var tessaApiKey by remember { mutableStateOf(config.resolvedTessaApiKey().ifBlank { DeveloperConfig.DEFAULT_TESSA_API_KEY }) }
    var tessaTestResultText by remember { mutableStateOf<String?>(null) }
    var isTestingTessa by remember { mutableStateOf(false) }

    var gmailEmail by remember { mutableStateOf(config.gmailConnectedEmail) }
    var gmailToken by remember { mutableStateOf(config.gmailOAuthAccessToken) }
    var gmailRefreshToken by remember { mutableStateOf(config.gmailOAuthRefreshToken) }
    var gmailMonitoring by remember { mutableStateOf(config.gmailMonitoringEnabled) }
    var gmailTestResultText by remember { mutableStateOf<String?>(null) }
    var isTestingGmail by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var testResultText by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .clip(RoundedCornerShape(20.dp)),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "MAYA DEVELOPER",
                            color = VermilionPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Safe Controls",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_developer_panel")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Specialist Controls", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-Detect Specialist", color = TextPrimary)
                    Switch(
                        checked = autoDetectSpecialist,
                        onCheckedChange = { autoDetectSpecialist = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = VermilionPrimary
                        )
                    )
                }

                if (!autoDetectSpecialist) {
                    Text("Manual Specialist Mode", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SpecialistType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedSpecialist == type,
                                onClick = { selectedSpecialist = type },
                                label = { Text(type.name, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VermilionPrimary,
                                    selectedLabelColor = TextPrimary,
                                    containerColor = DarkCard,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Behaviour", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = systemInst,
                    onValueChange = { systemInst = it },
                    label = { Text("System Instructions") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_system_instructions")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    label = { Text("Personality Prompt") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_personality")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = responseRules,
                    onValueChange = { responseRules = it },
                    label = { Text("Response Rules") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_response_rules")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Offline AI Diagnostics", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        isTesting = true
                        testResultText = "Running real llama.cpp offline test..."
                        coroutineScope.launch {
                            val res = ModelDownloadManager.testOfflineInference(context)
                            testResultText = res
                            isTesting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = VermilionPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isTesting) "Running Native Test..." else "Test Offline llama.cpp Engine", color = TextPrimary, fontWeight = FontWeight.Bold)
                }

                if (testResultText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = DarkCard,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = testResultText!!,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("API & Models", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = customKey,
                    onValueChange = { customKey = it },
                    label = { Text("Custom Gemini API Key (Optional)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_api_key")
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = customEndpoint,
                    onValueChange = { customEndpoint = it },
                    label = { Text("Custom Endpoint (Optional)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_endpoint")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Dual Build System (GitHub Actions & Cloud Run)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Text("Backend Selection Policy", color = TextSecondary, fontSize = 13.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("AUTO", "GITHUB_ACTIONS", "CLOUD_RUN").forEach { policy ->
                        FilterChip(
                            selected = backendPolicy.equals(policy, ignoreCase = true),
                            onClick = { backendPolicy = policy },
                            label = { Text(policy, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VermilionPrimary,
                                selectedLabelColor = TextPrimary,
                                containerColor = DarkCard,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Text("Preferred Artifact Type", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("APK", "AAB", "WEB").forEach { artType ->
                        FilterChip(
                            selected = preferredArtifact.equals(artType, ignoreCase = true),
                            onClick = { preferredArtifact = artType },
                            label = { Text(artType, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VermilionPrimary,
                                selectedLabelColor = TextPrimary,
                                containerColor = DarkCard,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = githubRepo,
                    onValueChange = { githubRepo = it },
                    label = { Text("GitHub Repository (owner/repo)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_github_repo")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = githubBranch,
                    onValueChange = { githubBranch = it },
                    label = { Text("GitHub Branch (e.g. main)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_github_branch")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = cloudRunUrl,
                    onValueChange = { cloudRunUrl = it },
                    label = { Text("Cloud Run Build Server URL") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_cloud_run_url")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = cloudRunKey,
                    onValueChange = { cloudRunKey = it },
                    label = { Text("Cloud Run API Key / Token (Optional)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VermilionPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = VermilionPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_cloud_run_key")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Runtime Features", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Face Animation", color = TextPrimary)
                    Switch(
                        checked = faceEnabled,
                        onCheckedChange = { faceEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = VermilionPrimary
                        )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Text-To-Speech", color = TextPrimary)
                    Switch(
                        checked = ttsEnabled,
                        onCheckedChange = { ttsEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = VermilionPrimary
                        )
                    )
                }

                if (ttsEnabled) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("Speech Rate (${String.format("%.2f", ttsRate)}x)", color = TextSecondary, fontSize = 13.sp)
                        Slider(
                            value = ttsRate,
                            onValueChange = { ttsRate = it },
                            valueRange = 0.5f..1.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = VermilionPrimary,
                                activeTrackColor = VermilionPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("TESSA Turbo Model Provider Configuration & Diagnostics", color = PurpleAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                val liveTessaConfig = remember(config, tessaModelId, tessaApiBaseUrl, tessaApiKey) {
                    config.copy(
                        tessaModelId = tessaModelId.trim(),
                        tessaApiBaseUrl = tessaApiBaseUrl.trim(),
                        tessaApiKey = tessaApiKey.trim()
                    )
                }
                val tessaHttpInspector = remember { com.cocateail.maya.tessa.TessaHttpModelProvider.sharedInstance }
                var tessaLiveDiagnostics by remember(liveTessaConfig) {
                    mutableStateOf(tessaHttpInspector.inspectDiagnostics(liveTessaConfig))
                }
                val isTessaFullyConfigured = liveTessaConfig.isTessaProviderConfigured()
                Surface(
                    color = DarkCard,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PurpleAccent.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tessa_developer_panel_section")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tessaLiveDiagnostics.runtimeStatus.name,
                                color = if (isTessaFullyConfigured) Color(0xFF38EF7D) else Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.testTag("tessa_runtime_status_badge")
                            )
                            Surface(
                                color = PurpleAccent.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "INDEPENDENT PROVIDER",
                                    color = PurpleAccent,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Separate dedicated provider for TESSA Turbo V1. Never reuses MAYA Online Gemini or MAYA Offline Qwen.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = DarkSurface,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tessa_diagnostics_checklist_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Provider class: ${tessaLiveDiagnostics.providerClass}",
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Model ID configured: ${if (tessaLiveDiagnostics.isModelIdConfigured) "YES" else "NO"}",
                                    color = if (tessaLiveDiagnostics.isModelIdConfigured) Color(0xFF38EF7D) else ErrorRed,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "API endpoint configured: ${if (tessaLiveDiagnostics.isEndpointConfigured) "YES" else "NO"}",
                                    color = if (tessaLiveDiagnostics.isEndpointConfigured) Color(0xFF38EF7D) else ErrorRed,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "API key configured: ${if (tessaLiveDiagnostics.isApiKeyConfigured) "YES" else "NO"}",
                                    color = if (tessaLiveDiagnostics.isApiKeyConfigured) Color(0xFF38EF7D) else ErrorRed,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Provider reachable: ${if (tessaLiveDiagnostics.isProviderReachable) "YES" else "NO"}",
                                    color = if (tessaLiveDiagnostics.isProviderReachable) Color(0xFF38EF7D) else TextSecondary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "TESSA runtime ready: ${if (tessaLiveDiagnostics.isRuntimeReady) "YES" else "NO"}",
                                    color = if (tessaLiveDiagnostics.isRuntimeReady) Color(0xFF38EF7D) else ErrorRed,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Connection test: ${tessaLiveDiagnostics.connectionResult}${tessaLiveDiagnostics.httpStatusCode?.let { " (HTTP $it)" } ?: ""}",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                                if (tessaLiveDiagnostics.endpointPath.isNotBlank()) {
                                    Text(
                                        text = "Endpoint: ${tessaLiveDiagnostics.endpointPath}",
                                        color = TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                                if (tessaLiveDiagnostics.sanitizedErrorBody.isNotBlank()) {
                                    Text(
                                        text = "Gemini Error Body: ${tessaLiveDiagnostics.sanitizedErrorBody}",
                                        color = ErrorRed,
                                        fontSize = 11.sp,
                                        modifier = Modifier.testTag("tessa_panel_sanitized_error_body")
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = tessaModelId,
                            onValueChange = { tessaModelId = it },
                            label = { Text("Tessa Model ID (default: gemini-3.8-flash)") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PurpleAccent,
                                unfocusedBorderColor = DarkBorder,
                                focusedLabelColor = PurpleAccent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_tessa_model_id")
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tessaApiBaseUrl,
                            onValueChange = { tessaApiBaseUrl = it },
                            label = { Text("Tessa API Base URL (e.g. https://generativelanguage.googleapis.com/v1beta)") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PurpleAccent,
                                unfocusedBorderColor = DarkBorder,
                                focusedLabelColor = PurpleAccent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_tessa_base_url")
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tessaApiKey,
                            onValueChange = { tessaApiKey = it },
                            label = { Text("Tessa API Key") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PurpleAccent,
                                unfocusedBorderColor = DarkBorder,
                                focusedLabelColor = PurpleAccent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_tessa_api_key")
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                isTestingTessa = true
                                tessaTestResultText = "Testing live TESSA dedicated provider connection..."
                                coroutineScope.launch {
                                    val diag = tessaHttpInspector.testConnection(liveTessaConfig)
                                    tessaLiveDiagnostics = diag
                                    tessaTestResultText = when (diag.runtimeStatus) {
                                        com.cocateail.maya.tessa.TessaRuntimeStatus.TESSA_READY ->
                                            "✅ TESSA_READY\n• Provider: ${diag.providerClass}\n• Model: ${liveTessaConfig.tessaModelId}\n• Status: ${diag.statusMessage}"
                                        com.cocateail.maya.tessa.TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED ->
                                            "⚠️ TESSA_CONFIGURATION_REQUIRED\n${diag.statusMessage}"
                                        com.cocateail.maya.tessa.TessaRuntimeStatus.TESSA_AUTH_REQUIRED ->
                                            "❌ TESSA_AUTH_REQUIRED\n${diag.statusMessage}"
                                        com.cocateail.maya.tessa.TessaRuntimeStatus.TESSA_PROVIDER_UNAVAILABLE ->
                                            "❌ TESSA_PROVIDER_UNAVAILABLE\n${diag.statusMessage}"
                                        com.cocateail.maya.tessa.TessaRuntimeStatus.TESSA_ERROR ->
                                            "❌ TESSA_ERROR\n${diag.statusMessage}"
                                    }
                                    isTestingTessa = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("test_tessa_connection_button"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, PurpleAccent)
                        ) {
                            Text(
                                if (isTestingTessa) "Testing TESSA Connection..." else "Test TESSA Connection",
                                color = PurpleAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        if (tessaTestResultText != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = tessaTestResultText!!,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkSurface, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                                    .testTag("tessa_connection_test_result")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("TESSA Gmail Integration", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                val isGmailConnected = gmailEmail.isNotBlank() && (gmailToken.isNotBlank() || gmailRefreshToken.isNotBlank())
                Surface(
                    color = DarkCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isGmailConnected) "Gmail: CONNECTED" else "Gmail: NOT CONNECTED",
                                color = if (isGmailConnected) Color(0xFF38EF7D) else TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            if (isGmailConnected) {
                                TextButton(
                                    onClick = {
                                        gmailEmail = ""
                                        gmailToken = ""
                                        gmailRefreshToken = ""
                                        gmailMonitoring = false
                                        com.cocateail.maya.tessa.gmail.TessaGmailMonitorWorker.cancel(context)
                                    }
                                ) {
                                    Text("Disconnect", color = ErrorRed, fontSize = 12.sp)
                                }
                            }
                        }

                        if (isGmailConnected) {
                            Text("Account: $gmailEmail", color = TextPrimary, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Permissions: Read Email, Read Attachments, Create Drafts, Send Email (Explicit)", color = TextSecondary, fontSize = 11.sp)
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = gmailEmail,
                                onValueChange = { gmailEmail = it },
                                label = { Text("Google Account Email") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PurpleAccent,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = gmailToken,
                                onValueChange = { gmailToken = it },
                                label = { Text("OAuth Access Token / Secret") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PurpleAccent,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("24h Background Monitor", color = TextPrimary, fontSize = 13.sp)
                            Switch(
                                checked = gmailMonitoring,
                                onCheckedChange = {
                                    gmailMonitoring = it
                                    if (it) {
                                        com.cocateail.maya.tessa.gmail.TessaGmailMonitorWorker.schedule(context)
                                    } else {
                                        com.cocateail.maya.tessa.gmail.TessaGmailMonitorWorker.cancel(context)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = PurpleAccent
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                isTestingGmail = true
                                gmailTestResultText = "Testing live official Gmail REST API..."
                                coroutineScope.launch {
                                    val testConfig = config.copy(
                                        gmailConnectedEmail = gmailEmail.trim(),
                                        gmailOAuthAccessToken = gmailToken.trim(),
                                        gmailOAuthRefreshToken = gmailRefreshToken.trim()
                                    )
                                    val testConnector = com.cocateail.maya.tessa.gmail.RealGmailConnector(
                                        context = context,
                                        configProvider = { testConfig }
                                    )
                                    val res = testConnector.getProfile()
                                    gmailTestResultText = res.fold(
                                        onSuccess = { profile ->
                                            "✅ Real Gmail Connection Successful!\n• Account: ${profile.emailAddress}\n• Total Messages: ${profile.messagesTotal}\n• Total Threads: ${profile.threadsTotal}"
                                        },
                                        onFailure = { err ->
                                            "❌ Gmail Connection Error: ${err.message}"
                                        }
                                    )
                                    isTestingGmail = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                if (isTestingGmail) "Verifying Gmail API..." else "Test Live Gmail API",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        if (gmailTestResultText != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = gmailTestResultText!!,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        onSave(
                            config.copy(
                                systemInstructions = systemInst,
                                personality = personality,
                                responseRules = responseRules,
                                ttsEnabled = ttsEnabled,
                                ttsRate = ttsRate,
                                faceEnabled = faceEnabled,
                                customApiKey = customKey,
                                customEndpoint = customEndpoint,
                                autoDetectSpecialist = autoDetectSpecialist,
                                selectedSpecialist = selectedSpecialist,
                                maxFixAttempts = maxFixAttempts,
                                githubRepository = githubRepo.trim(),
                                githubBranch = githubBranch.trim(),
                                cloudRunBuildUrl = cloudRunUrl.trim(),
                                remoteBuildServerUrl = cloudRunUrl.trim(),
                                cloudRunApiKey = cloudRunKey.trim(),
                                remoteBuildApiKey = cloudRunKey.trim(),
                                backendSelectionPolicy = backendPolicy,
                                preferredArtifactType = preferredArtifact,
                                tessaModelId = tessaModelId.trim(),
                                tessaApiBaseUrl = tessaApiBaseUrl.trim(),
                                tessaApiKey = tessaApiKey.trim(),
                                gmailConnectedEmail = gmailEmail.trim(),
                                gmailOAuthAccessToken = gmailToken.trim(),
                                gmailOAuthRefreshToken = gmailRefreshToken.trim(),
                                gmailMonitoringEnabled = gmailMonitoring
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_developer_config_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VermilionPrimary,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Controls", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
