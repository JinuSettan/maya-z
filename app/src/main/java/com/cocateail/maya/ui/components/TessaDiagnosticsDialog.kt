package com.cocateail.maya.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.tessa.TessaDiagnosticsInfo
import com.cocateail.maya.tessa.TessaHttpModelProvider
import com.cocateail.maya.tessa.TessaRuntimeStatus
import com.cocateail.maya.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TessaDiagnosticsDialog(
    config: DeveloperConfig,
    onDismiss: () -> Unit,
    onOpenDeveloperConfig: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val httpProvider = remember { TessaHttpModelProvider.sharedInstance }

    var diagnostics by remember(config) { mutableStateOf(httpProvider.inspectDiagnostics(config)) }
    var isRunningTest by remember { mutableStateOf(false) }

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
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TESSA TURBO V1.2",
                            color = PurpleAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Provider Diagnostics",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_tessa_diagnostics_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Runtime Status Badge
                Surface(
                    color = Color(diagnostics.runtimeStatus.badgeColorHex).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(diagnostics.runtimeStatus.badgeColorHex)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = when (diagnostics.runtimeStatus) {
                                TessaRuntimeStatus.TESSA_READY -> Icons.Default.CheckCircle
                                TessaRuntimeStatus.TESSA_CONFIGURATION_REQUIRED -> Icons.Default.Warning
                                else -> Icons.Default.Error
                            },
                            contentDescription = null,
                            tint = Color(diagnostics.runtimeStatus.badgeColorHex),
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = diagnostics.runtimeStatus.name,
                                color = Color(diagnostics.runtimeStatus.badgeColorHex),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = diagnostics.statusMessage,
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Runtime Checklist", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = DarkCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DiagnosticCheckRow(
                            label = "Provider Class",
                            value = diagnostics.providerClass,
                            isOk = true
                        )
                        DiagnosticCheckRow(
                            label = "Model ID configured",
                            value = if (diagnostics.isModelIdConfigured) "YES (${config.tessaModelId})" else "NO",
                            isOk = diagnostics.isModelIdConfigured
                        )
                        DiagnosticCheckRow(
                            label = "API endpoint configured",
                            value = if (diagnostics.isEndpointConfigured) "YES (${config.tessaApiBaseUrl.take(24)}...)" else "NO",
                            isOk = diagnostics.isEndpointConfigured
                        )
                        DiagnosticCheckRow(
                            label = "API key configured",
                            value = if (diagnostics.isApiKeyConfigured) "YES (●●●●●●●●)" else "NO",
                            isOk = diagnostics.isApiKeyConfigured
                        )
                        DiagnosticCheckRow(
                            label = "Provider reachable",
                            value = if (diagnostics.isProviderReachable) "YES" else "NO",
                            isOk = diagnostics.isProviderReachable
                        )
                        DiagnosticCheckRow(
                            label = "TESSA runtime ready",
                            value = if (diagnostics.isRuntimeReady) "YES" else "NO",
                            isOk = diagnostics.isRuntimeReady
                        )
                        DiagnosticCheckRow(
                            label = "Connection test result",
                            value = diagnostics.connectionResult + (diagnostics.httpStatusCode?.let { " (HTTP $it)" } ?: ""),
                            isOk = diagnostics.connectionResult == "SUCCESS"
                        )
                        if (diagnostics.httpStatusCode != null) {
                            DiagnosticCheckRow(
                                label = "HTTP status code",
                                value = "HTTP ${diagnostics.httpStatusCode}",
                                isOk = diagnostics.httpStatusCode in 200..299
                            )
                        }
                        if (diagnostics.retryAttempts > 0) {
                            DiagnosticCheckRow(
                                label = "503 retry attempts",
                                value = "${diagnostics.retryAttempts}",
                                isOk = diagnostics.connectionResult == "SUCCESS"
                            )
                        }
                        if (diagnostics.errorCategory != "NONE") {
                            DiagnosticCheckRow(
                                label = "Error category",
                                value = diagnostics.errorCategory,
                                isOk = false
                            )
                        }
                        if (diagnostics.latencyMs != null) {
                            DiagnosticCheckRow(
                                label = "Latency",
                                value = "${diagnostics.latencyMs}ms",
                                isOk = true
                            )
                        }
                    }
                }

                if (diagnostics.endpointPath.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = DarkCard,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Target Endpoint (Sanitized)", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = diagnostics.endpointPath,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                modifier = Modifier.testTag("tessa_diag_endpoint_path")
                            )
                        }
                    }
                }

                if (diagnostics.sanitizedErrorBody.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = ErrorRed.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Sanitized Gemini Error Body${diagnostics.httpStatusCode?.let { " (HTTP $it)" } ?: ""}",
                                color = ErrorRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = diagnostics.sanitizedErrorBody,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                modifier = Modifier.testTag("tessa_diag_sanitized_error_body")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Actions: Test Connection + Configure
                Button(
                    onClick = {
                        isRunningTest = true
                        coroutineScope.launch {
                            diagnostics = httpProvider.testConnection(config)
                            isRunningTest = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("run_tessa_health_test_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PurpleAccent,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isRunningTest) {
                        CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing Live Connection...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test TESSA Connection", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenDeveloperConfig()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("open_tessa_config_from_diag_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    Text("Configure in Developer Panel", color = TextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCheckRow(
    label: String,
    value: String,
    isOk: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                color = if (isOk) Color(0xFF38EF7D) else ErrorRed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }
    }
}
