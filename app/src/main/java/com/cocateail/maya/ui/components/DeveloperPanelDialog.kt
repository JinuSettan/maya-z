package com.cocateail.maya.ui.components

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
                                maxFixAttempts = maxFixAttempts
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
