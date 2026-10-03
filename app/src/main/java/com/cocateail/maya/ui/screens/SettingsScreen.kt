package com.cocateail.maya.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Circle
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
import androidx.work.ExistingWorkPolicy
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.data.model.ModelDownloadManager
import com.cocateail.maya.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedCharacter: MayaCharacter,
    developerUnlocked: Boolean,
    onSelectCharacter: (MayaCharacter) -> Unit,
    onOpenDeveloperPanel: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = VermilionPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text("AI Companion Identity", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Select your preferred AI companion personality.",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
            )

            MayaCharacter.entries.forEach { character ->
                val isSelected = character == selectedCharacter
                val characterAccent = if (character == MayaCharacter.ROCKY) PurpleAccent else VermilionPrimary

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(DarkSurface)
                        .border(
                            width = 1.5.dp,
                            color = if (isSelected) characterAccent else DarkBorder,
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable { onSelectCharacter(character) }
                        .padding(16.dp)
                        .testTag("select_character_${character.name}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) characterAccent.copy(alpha = 0.2f) else DarkCard)
                            .border(1.dp, if (isSelected) characterAccent else DarkBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = character.avatarLetter,
                            color = if (isSelected) characterAccent else TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(character.displayName, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(character.description, color = TextSecondary, fontSize = 13.sp)
                    }

                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (isSelected) characterAccent else TextMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Offline AI Model Section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = VermilionPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text("Offline AI Model", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Qwen2.5 0.5B Instruct GGUF model for native offline AI execution.",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            val context = LocalContext.current
            val downloadInfo by ModelDownloadManager.downloadInfo.collectAsState()

            LaunchedEffect(Unit) {
                ModelDownloadManager.isModelReady(context)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Qwen2.5-0.5B (Q4_0 GGUF)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                text = downloadInfo.statusMessage,
                                color = when (downloadInfo.status) {
                                    ModelDownloadManager.ModelStatus.READY -> SuccessGreen
                                    ModelDownloadManager.ModelStatus.DOWNLOADING -> VermilionPrimary
                                    ModelDownloadManager.ModelStatus.FAILED -> ErrorRed
                                    else -> TextSecondary
                                },
                                fontSize = 13.sp
                            )
                        }

                        if (downloadInfo.status == ModelDownloadManager.ModelStatus.READY) {
                            Surface(
                                color = SuccessGreen.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, SuccessGreen),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(14.dp))
                                    Text("ACTIVE", color = SuccessGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (downloadInfo.status == ModelDownloadManager.ModelStatus.DOWNLOADING || downloadInfo.downloadedBytes > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (downloadInfo.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = VermilionPrimary,
                            trackColor = DarkCard
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${downloadInfo.progressPercent}% downloaded", color = TextSecondary, fontSize = 12.sp)
                            Text("${downloadInfo.downloadedBytes / (1024 * 1024)} MB / 409 MB", color = TextSecondary, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            ModelDownloadManager.triggerManualDownload(context, ExistingWorkPolicy.REPLACE)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (downloadInfo.status == ModelDownloadManager.ModelStatus.READY) DarkCard else VermilionPrimary,
                            contentColor = TextPrimary
                        ),
                        border = if (downloadInfo.status == ModelDownloadManager.ModelStatus.READY) BorderStroke(1.dp, SuccessGreen) else null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("download_offline_model_button")
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (downloadInfo.status == ModelDownloadManager.ModelStatus.READY) SuccessGreen else TextPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (downloadInfo.status) {
                                ModelDownloadManager.ModelStatus.READY -> "Re-Download / Verify Model (409 MB)"
                                ModelDownloadManager.ModelStatus.DOWNLOADING -> "Force Start / Restart Download (409 MB)"
                                ModelDownloadManager.ModelStatus.FAILED -> "Retry Download (409 MB)"
                                else -> "Download Offline AI Model (409 MB)"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeveloperMode,
                    contentDescription = null,
                    tint = PurpleAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text("Developer Options", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = if (developerUnlocked) "Developer controls & specialist overrides are active." else "Type /developerceo/maya/unlockcode/trainer in chat to unlock developer controls.",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            if (developerUnlocked) {
                Button(
                    onClick = onOpenDeveloperPanel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PurpleAccent,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_developer_panel_button")
                ) {
                    Text("Open Developer Panel", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
