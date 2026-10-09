package com.cocateail.maya.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cocateail.maya.tessa.*
import com.cocateail.maya.tessa.computer.ComputerCapability
import com.cocateail.maya.tessa.computer.ComputerPairingStatus
import com.cocateail.maya.tessa.computer.PairedComputerInfo
import com.cocateail.maya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TessaComputerBrowserDialog(
    pairedComputer: PairedComputerInfo?,
    activeTask: TessaTask?,
    uiStatus: TessaUiStatus,
    onDismiss: () -> Unit,
    onPairComputer: (computerId: String, deviceName: String, token: String, host: String, port: Int) -> Unit = { _, _, _, _, _ -> },
    onReconnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onApprovePermission: (taskId: String, reusable: Boolean) -> Unit = { _, _ -> },
    onDenyPermission: (taskId: String) -> Unit = {}
) {
    var showPairingInput by remember { mutableStateOf(false) }
    var hostInput by remember { mutableStateOf("192.168.1.100") }
    var portInput by remember { mutableStateOf("8990") }
    var deviceNameInput by remember { mutableStateOf("My PC / Workstation") }
    var tokenInput by remember { mutableStateOf("secret_tessa_token") }

    val isConnected = pairedComputer?.connectionStatus == ComputerPairingStatus.CONNECTED

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, PurpleAccent.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .testTag("tessa_computer_browser_dialog"),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = PurpleAccent.copy(alpha = 0.2f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Computer,
                                    contentDescription = "Computer Agent",
                                    tint = PurpleAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "TESSA TURBO V1.2",
                                color = TextPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Local Computer & Browser Autonomous Agent",
                                color = PurpleAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("dialog_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Computer Connection Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkCard,
                    border = BorderStroke(1.dp, if (isConnected) SuccessGreen.copy(alpha = 0.5f) else DarkBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("computer_connection_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Computer",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(if (isConnected) SuccessGreen else TextMuted, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConnected) "🟢 Connected" else "⚪ Disconnected",
                                    color = if (isConnected) SuccessGreen else TextMuted,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = pairedComputer?.deviceName ?: "No Authorized Computer Paired",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        if (pairedComputer != null) {
                            Text(
                                text = "Host: ${pairedComputer.hostAddress}:${pairedComputer.port} • Root: ${pairedComputer.workspaceRoot}",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isConnected) {
                                Button(
                                    onClick = { showPairingInput = !showPairingInput },
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp).testTag("pair_computer_button")
                                ) {
                                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pair Computer", fontSize = 12.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onReconnect,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp).testTag("reconnect_button")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Ping / Reconnect", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = onDisconnect,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(36.dp).testTag("disconnect_button")
                                ) {
                                    Text("Disconnect", fontSize = 12.sp, color = ErrorRed)
                                }
                            }
                        }

                        // Pairing Input Expansion
                        if (showPairingInput) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider(color = DarkBorder)
                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = deviceNameInput,
                                onValueChange = { deviceNameInput = it },
                                label = { Text("Device Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("device_name_input")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = hostInput,
                                    onValueChange = { hostInput = it },
                                    label = { Text("IP / Host") },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f).testTag("host_input")
                                )
                                OutlinedTextField(
                                    value = portInput,
                                    onValueChange = { portInput = it },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("port_input")
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                label = { Text("Pairing Token") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("pairing_token_input")
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val port = portInput.toIntOrNull() ?: 8990
                                    onPairComputer(
                                        "comp-" + java.util.UUID.randomUUID().toString().take(8),
                                        deviceNameInput,
                                        tokenInput,
                                        hostInput,
                                        port
                                    )
                                    showPairingInput = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                modifier = Modifier.fillMaxWidth().testTag("confirm_pair_button")
                            ) {
                                Text("Authorize & Connect", color = TextPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Capabilities Checklist
                Text(
                    text = "Capabilities",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val caps = listOf("Files", "Terminal", "Browser", "Unreal Engine", "Git")
                    for (cap in caps) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkCard,
                            border = BorderStroke(1.dp, if (isConnected) PurpleAccent.copy(alpha = 0.5f) else DarkBorder),
                            modifier = Modifier.testTag("cap_badge_$cap")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isConnected) "✓ $cap" else "• $cap",
                                    fontSize = 11.sp,
                                    color = if (isConnected) TextPrimary else TextMuted,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Current Task & Live Progress Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkCard,
                    border = BorderStroke(1.dp, PurpleAccent.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("current_task_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Current Task",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val taskTitle = if (activeTask != null) {
                                activeTask.userRequest.ifBlank { "Task ${activeTask.taskId.take(12)}" }
                            } else {
                                "No Active Computer Task"
                            }
                            Text(
                                text = "💻 $taskTitle",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        val progress = if (activeTask != null && activeTask.totalSteps > 0) {
                            if (activeTask.status == TessaTaskState.COMPLETED) {
                                1.0f
                            } else {
                                (activeTask.currentStep.toFloat() / activeTask.totalSteps.toFloat()).coerceIn(0f, 1f)
                            }
                        } else {
                            0.0f
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Progress", color = TextSecondary, fontSize = 11.sp)
                            val progressPercent = (progress * 100).toInt()
                            val progressLabel = if (activeTask?.status == TessaTaskState.COMPLETED) {
                                "100% (Completed)"
                            } else if (activeTask?.status == TessaTaskState.FAILED) {
                                "$progressPercent% (Failed)"
                            } else {
                                "$progressPercent%"
                            }
                            Text(progressLabel, color = PurpleAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = when (activeTask?.status) {
                                TessaTaskState.COMPLETED -> SuccessGreen
                                TessaTaskState.FAILED -> ErrorRed
                                else -> PurpleAccent
                            },
                            trackColor = DarkSurface
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Current Action",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val actionText = when {
                            activeTask == null -> "Idle — waiting for task instructions"
                            activeTask.status == TessaTaskState.COMPLETED -> "✅ Task completed and verified"
                            activeTask.status == TessaTaskState.FAILED -> "❌ Execution stopped (${activeTask.error ?: "Failure"})"
                            activeTask.status == TessaTaskState.WAITING_FOR_PERMISSION -> "🔐 Waiting for user permission"
                            activeTask.status == TessaTaskState.WAITING_FOR_USER_AUTHENTICATION -> "🔑 Waiting for user authentication"
                            activeTask.status == TessaTaskState.WAITING_FOR_USER_DECISION -> "⚠️ Waiting for user decision"
                            else -> "⚙️ Running (${activeTask.targetTool})"
                        }
                        Text(
                            text = actionText,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Status",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusLabel = when (activeTask?.status) {
                                TessaTaskState.COMPLETED -> "✅ COMPLETED"
                                TessaTaskState.FAILED -> "❌ FAILED: ${activeTask.error ?: "Execution failed"}"
                                TessaTaskState.WAITING_FOR_PERMISSION -> "🔐 WAITING FOR PERMISSION"
                                TessaTaskState.WAITING_FOR_USER_AUTHENTICATION -> "🔑 WAITING FOR AUTHENTICATION"
                                TessaTaskState.WAITING_FOR_USER_DECISION -> "⚠️ WAITING FOR DECISION"
                                TessaTaskState.RUNNING -> "⚙️ RUNNING"
                                TessaTaskState.QUEUED -> "⏳ QUEUED"
                                null -> if (isConnected) "🟢 READY (IDLE)" else "⚪ DISCONNECTED"
                                else -> activeTask.status.name
                            }
                            val statusColor = when (activeTask?.status) {
                                TessaTaskState.COMPLETED -> SuccessGreen
                                TessaTaskState.FAILED -> ErrorRed
                                TessaTaskState.WAITING_FOR_PERMISSION,
                                TessaTaskState.WAITING_FOR_USER_AUTHENTICATION,
                                TessaTaskState.WAITING_FOR_USER_DECISION -> WarningOrange
                                TessaTaskState.RUNNING -> PurpleAccent
                                null -> if (isConnected) SuccessGreen else TextMuted
                                else -> TextSecondary
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = statusColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = statusLabel,
                                    color = statusColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "TESSA",
                            color = PurpleAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val tessaMsg = when (activeTask?.status) {
                            TessaTaskState.COMPLETED -> activeTask.result ?: "Task execution finished and verified successfully."
                            TessaTaskState.FAILED -> activeTask.error ?: "Task execution failed. Diagnostics available."
                            TessaTaskState.WAITING_FOR_PERMISSION -> "Waiting for approval to execute required permissions."
                            TessaTaskState.WAITING_FOR_USER_AUTHENTICATION -> "Waiting for user authentication / credentials on device."
                            TessaTaskState.WAITING_FOR_USER_DECISION -> "Waiting for user decision on inspection details."
                            TessaTaskState.RUNNING -> "Executing task with permission controls and safety monitoring."
                            null -> if (isConnected) "Connected to computer companion. Ready to accept commands." else "No authorized computer companion currently paired."
                            else -> "Task state: ${activeTask.status.name}"
                        }
                        Text(
                            text = tessaMsg,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                // Permission Approval Card (if waiting for permission)
                if (activeTask?.status == TessaTaskState.WAITING_FOR_PERMISSION) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = DarkCard,
                        border = BorderStroke(1.dp, VermilionPrimary.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("permission_approval_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = VermilionPrimary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Permission Confirmation Required",
                                    color = VermilionPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "TESSA requests permission to: ${activeTask.requiredPermissions.joinToString { it.name }}",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onApprovePermission(activeTask.taskId, false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                                    modifier = Modifier.height(36.dp).testTag("approve_perm_btn")
                                ) {
                                    Text("Approve Once", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { onApprovePermission(activeTask.taskId, true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkBorder),
                                    modifier = Modifier.height(36.dp).testTag("approve_always_btn")
                                ) {
                                    Text("Always Allow", fontSize = 11.sp, color = TextPrimary)
                                }
                                OutlinedButton(
                                    onClick = { onDenyPermission(activeTask.taskId) },
                                    modifier = Modifier.height(36.dp).testTag("deny_perm_btn")
                                ) {
                                    Text("Deny", fontSize = 11.sp, color = ErrorRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
