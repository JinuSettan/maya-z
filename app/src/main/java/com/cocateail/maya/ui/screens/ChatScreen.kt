package com.cocateail.maya.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cocateail.maya.data.db.ConversationEntity
import com.cocateail.maya.data.db.MessageEntity
import com.cocateail.maya.data.model.DeveloperConfig
import com.cocateail.maya.data.model.MayaCharacter
import com.cocateail.maya.data.specialist.SpecialistType
import com.cocateail.maya.data.specialist.TaskClassifier
import com.cocateail.maya.tessa.AssistantEngineMode
import com.cocateail.maya.tessa.TessaTask
import com.cocateail.maya.tessa.TessaTaskState
import com.cocateail.maya.tessa.TessaUiStatus
import com.cocateail.maya.ui.components.ChatBubble
import com.cocateail.maya.ui.components.ConversationDrawerContent
import com.cocateail.maya.ui.components.FaceMode
import com.cocateail.maya.ui.components.MayaFaceView
import com.cocateail.maya.ui.components.SpecialistBadge
import com.cocateail.maya.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversations: List<ConversationEntity>,
    activeConversationId: String?,
    messages: List<MessageEntity>,
    character: MayaCharacter,
    developerConfig: DeveloperConfig,
    developerUnlocked: Boolean = developerConfig.developerUnlocked,
    isThinking: Boolean,
    isSpeaking: Boolean,
    activeReplyToMessage: MessageEntity? = null,
    tessaTasks: List<TessaTask> = emptyList(),
    tessaUiStatus: TessaUiStatus = TessaUiStatus.READY,
    onSelectAssistantMode: (AssistantEngineMode) -> Unit = {},
    onApproveTessaPermission: (String, Boolean) -> Unit = { _, _ -> },
    onDenyTessaPermission: (String) -> Unit = {},
    onCancelTessaTask: (String) -> Unit = {},
    onReplyToMessage: (MessageEntity?) -> Unit = {},
    onSendMessage: (String, Bitmap?, String?) -> Unit,
    onSendMessageDirect: suspend (String) -> String = { "I'm right here listening to you." },
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onPinMessage: (String, Boolean) -> Unit,
    onSpeakMessage: (String) -> Unit,
    onOpenDeveloperPanel: () -> Unit = {},
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Search state
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isVoiceCallActive by remember { mutableStateOf(false) }
    var isMediaStudioActive by remember { mutableStateOf(false) }
    var isAppGameStudioActive by remember { mutableStateOf(false) }
    var isTessaComputerDialogOpen by remember { mutableStateOf(false) }
    var isTessaDiagnosticsDialogOpen by remember { mutableStateOf(false) }

    val accentColor = if (character == MayaCharacter.ROCKY) PurpleAccent else VermilionPrimary

    // Detect specialist type based on input text or active developer config
    val activeSpecialist = remember(inputText, developerConfig) {
        if (developerConfig.autoDetectSpecialist) {
            TaskClassifier.classify(inputText)
        } else {
            developerConfig.selectedSpecialist
        }
    }

    val displayedMessages = remember(messages, searchQuery, isSearchActive) {
        if (isSearchActive && searchQuery.isNotBlank()) {
            messages.filter { msg ->
                msg.content.contains(searchQuery, ignoreCase = true)
            }
        } else {
            messages
        }
    }

    val doSendMessage = {
        val textToSend = inputText.trim()
        val bmp = selectedBitmap
        val imgUri = selectedImageUri?.toString()
        if (textToSend.isNotBlank() || bmp != null) {
            inputText = ""
            selectedImageUri = null
            selectedBitmap = null
            keyboardController?.hide()
            onSendMessage(textToSend, bmp, imgUri)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            try {
                selectedBitmap = if (Build.VERSION.SDK_INT < 28) {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                } else {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(displayedMessages.size, isThinking) {
        if (displayedMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayedMessages.size - 1)
        }
    }

    val activeMode = remember(developerConfig.activeAssistantMode) {
        AssistantEngineMode.fromConfigString(developerConfig.activeAssistantMode)
    }
    val isOffline = activeMode == AssistantEngineMode.MAYA_OFFLINE || !developerConfig.onlineFeaturesEnabled
    val statusText = when (activeMode) {
        AssistantEngineMode.SIMBA_TURBO_V2 -> "Locked 🔒"
        AssistantEngineMode.TESSA_TURBO_V1 -> tessaUiStatus.displayLabel
        AssistantEngineMode.MAYA_OFFLINE -> if (isThinking) "Thinking (Local GGUF)…" else "Offline (Qwen2.5)"
        AssistantEngineMode.MAYA_ONLINE -> when {
            isThinking -> "Thinking…"
            isSpeaking -> "Speaking…"
            else -> "Online"
        }
    }
    val activeIdentityTitle = when (activeMode) {
        AssistantEngineMode.TESSA_TURBO_V1 -> "TESSA Turbo V1"
        AssistantEngineMode.SIMBA_TURBO_V2 -> "SIMBA Turbo V2 🔒"
        else -> character.displayName
    }

    val faceMode = when {
        isOffline -> FaceMode.OFFLINE
        isSpeaking -> FaceMode.SPEAKING
        isThinking -> FaceMode.THINKING
        else -> FaceMode.IDLE
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = DarkSurface) {
                ConversationDrawerContent(
                    conversations = conversations,
                    activeConversationId = activeConversationId,
                    onSelectConversation = { id ->
                        onSelectConversation(id)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onNewConversation = {
                        onNewConversation()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onDeleteConversation = onDeleteConversation
                )
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.statusBars,
            topBar = {
                TopAppBar(
                    title = {
                        if (isSearchActive) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search messages...", color = TextMuted, fontSize = 14.sp) },
                                singleLine = true,
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = TextSecondary)
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkCard,
                                    unfocusedContainerColor = DarkCard,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = accentColor,
                                    unfocusedBorderColor = DarkBorder
                                ),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("chat_search_text_field")
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (developerConfig.faceEnabled) {
                                    MayaFaceView(
                                        mode = faceMode,
                                        enabled = true,
                                        size = 50.dp,
                                        accentColor = accentColor
                                    )
                                }
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = activeIdentityTitle,
                                            color = TextPrimary,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        if (activeMode == AssistantEngineMode.MAYA_ONLINE && activeSpecialist != SpecialistType.GENERAL) {
                                            SpecialistBadge(specialistType = activeSpecialist)
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        isOffline -> Color.Gray
                                                        isThinking || isSpeaking -> accentColor
                                                        else -> Color(0xFF38EF7D)
                                                    }
                                                )
                                        )
                                        Text(
                                            text = statusText,
                                            color = if (isThinking || isSpeaking) accentColor else TextSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        if (isSearchActive) {
                            IconButton(
                                onClick = {
                                    isSearchActive = false
                                    searchQuery = ""
                                },
                                modifier = Modifier.testTag("exit_search_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Search", tint = TextPrimary)
                            }
                        } else {
                            IconButton(
                                onClick = { coroutineScope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("open_conversations_drawer")
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = "Conversations", tint = TextPrimary)
                            }
                        }
                    },
                    actions = {
                        if (!isSearchActive) {
                            if (developerUnlocked) {
                                IconButton(
                                    onClick = onOpenDeveloperPanel,
                                    modifier = Modifier.testTag("open_developer_panel_chat_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeveloperMode,
                                        contentDescription = "Open Developer Panel",
                                        tint = PurpleAccent
                                    )
                                }
                            }
                            IconButton(
                                onClick = { isVoiceCallActive = true },
                                modifier = Modifier.testTag("start_voice_call_button")
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Start Voice Call", tint = accentColor)
                            }
                            IconButton(
                                onClick = { isSearchActive = true },
                                modifier = Modifier.testTag("open_chat_search_button")
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Search Messages", tint = TextPrimary)
                            }
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.testTag("open_settings_button")
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                            }
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
                    .imePadding()
            ) {
                // Assistant Mode Selector Bar (MAYA Offline | MAYA Online | TESSA Turbo V1 | SIMBA Turbo V2 🔒)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistantEngineMode.entries.forEach { mode ->
                        val selected = activeMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectAssistantMode(mode) },
                            label = {
                                Text(
                                    text = mode.displayName,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = when (mode) {
                                    AssistantEngineMode.TESSA_TURBO_V1 -> PurpleAccent
                                    AssistantEngineMode.SIMBA_TURBO_V2 -> DarkBorder
                                    else -> accentColor
                                },
                                selectedLabelColor = TextPrimary,
                                containerColor = DarkCard,
                                labelColor = if (mode.isLocked) TextMuted else TextSecondary
                            ),
                            modifier = Modifier.testTag("mode_selector_${mode.modeId}")
                        )
                    }
                }

                // SIMBA Turbo V2 Locked State Banner
                if (activeMode == AssistantEngineMode.SIMBA_TURBO_V2) {
                    Surface(
                        color = DarkCard,
                        border = BorderStroke(1.dp, DarkBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("simba_locked_banner")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "🔒 SIMBA Turbo V2 — Locked (Coming Soon)",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "SIMBA Turbo V2 is reserved for a future major update. No AI model, tools, or background tasks are started while SIMBA is selected.",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // TESSA Turbo V1 Live Task & Permission Banner
                if (activeMode == AssistantEngineMode.TESSA_TURBO_V1) {
                    val latestTask = tessaTasks.firstOrNull()
                    Surface(
                        color = DarkCard,
                        border = BorderStroke(1.dp, PurpleAccent.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("tessa_status_banner")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⚡ TESSA Turbo V1.2 • ${tessaUiStatus.displayLabel}",
                                    color = PurpleAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilledTonalButton(
                                        onClick = { isTessaDiagnosticsDialogOpen = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = PurpleAccent.copy(alpha = 0.2f)),
                                        modifier = Modifier.height(26.dp).testTag("open_tessa_diagnostics_button")
                                    ) {
                                        Text(
                                            text = if (developerConfig.isTessaProviderConfigured()) "Diagnostics" else "Configure Provider",
                                            color = if (developerConfig.isTessaProviderConfigured()) PurpleAccent else Color(0xFFFF9800),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = { isTessaComputerDialogOpen = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp).testTag("open_tessa_computer_dialog_button")
                                    ) {
                                        Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Computer Agent", fontSize = 10.sp)
                                    }
                                }
                            }
                            if (latestTask != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Task ${latestTask.taskId.take(15)} • ${latestTask.taskType.name} • ${latestTask.status.name} (Step ${latestTask.currentStep}/${latestTask.totalSteps})",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (latestTask.status == TessaTaskState.WAITING_FOR_PERMISSION) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Permissions requested: ${latestTask.requiredPermissions.joinToString { it.name }}",
                                        color = VermilionPrimary,
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { onApproveTessaPermission(latestTask.taskId, false) },
                                            colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .height(34.dp)
                                                .testTag("tessa_approve_once_button")
                                        ) {
                                            Text("Approve Once", fontSize = 11.sp, color = TextPrimary)
                                        }
                                        OutlinedButton(
                                            onClick = { onDenyTessaPermission(latestTask.taskId) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .height(34.dp)
                                                .testTag("tessa_deny_button")
                                        ) {
                                            Text("Deny", fontSize = 11.sp, color = ErrorRed)
                                        }
                                    }
                                } else if (latestTask.status == TessaTaskState.RUNNING || latestTask.status == TessaTaskState.QUEUED) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = { onCancelTessaTask(latestTask.taskId) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .height(34.dp)
                                            .testTag("tessa_cancel_task_button")
                                    ) {
                                        Text("Cancel Task", fontSize = 11.sp, color = ErrorRed)
                                    }
                                }
                            }
                        }
                    }
                }

                // Search Banner Status
                if (isSearchActive && searchQuery.isNotBlank()) {
                    Surface(
                        color = DarkCard,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔍 ${displayedMessages.size} matching message(s)",
                                color = accentColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("Clear filter", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Messages List or Search Empty State
                if (isSearchActive && searchQuery.isNotBlank() && displayedMessages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No messages found for \"$searchQuery\"",
                                color = TextMuted,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("Show all messages", color = accentColor)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(displayedMessages, key = { it.id }) { msg ->
                            ChatBubble(
                                message = msg,
                                characterName = character.displayName,
                                accentColor = accentColor,
                                onPinClick = { onPinMessage(msg.id, msg.pinned) },
                                onSpeakClick = { onSpeakMessage(msg.content) },
                                onReplyClick = { onReplyToMessage(msg) },
                                onQuoteClick = { targetId ->
                                    val index = displayedMessages.indexOfFirst { it.id == targetId }
                                    if (index >= 0) {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(index)
                                        }
                                    }
                                }
                            )
                        }

                        if (isThinking && !isSearchActive) {
                            item {
                                ThinkingIndicator(
                                    characterName = character.displayName,
                                    accentColor = accentColor
                                )
                            }
                        }
                    }
                }

                // Input Composer Surface
                Surface(
                    color = DarkSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        // Reply Preview Card
                        activeReplyToMessage?.let { reply ->
                            val senderName = if (reply.role == "user") "You" else character.displayName
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkCard)
                                    .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(accentColor)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Replying to $senderName",
                                            color = accentColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = reply.content,
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onReplyToMessage(null) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cancel reply",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Attachment Thumbnail Card
                        if (selectedImageUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(DarkCard)
                                    .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AsyncImage(
                                        model = selectedImageUri,
                                        contentDescription = "Attached Thumbnail",
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Column {
                                        Text("Photo attached", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("Ready for vision analysis", color = TextSecondary, fontSize = 11.sp)
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        selectedImageUri = null
                                        selectedBitmap = null
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove photo",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // When not typing: Show quick studio buttons
                            AnimatedVisibility(
                                visible = inputText.isEmpty(),
                                enter = fadeIn() + expandHorizontally(),
                                exit = fadeOut() + shrinkHorizontally()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            photoPickerLauncher.launch(
                                                androidx.activity.result.PickVisualMediaRequest(
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            )
                                        },
                                        modifier = Modifier
                                            .size(38.dp)
                                            .testTag("attach_photo_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AddPhotoAlternate,
                                            contentDescription = "Attach Photo",
                                            tint = accentColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { isMediaStudioActive = true },
                                        modifier = Modifier
                                            .size(38.dp)
                                            .testTag("open_media_studio_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "AI Media Studio (Image & Video Generator)",
                                            tint = accentColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { isAppGameStudioActive = true },
                                        modifier = Modifier
                                            .size(38.dp)
                                            .testTag("open_app_game_studio_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SportsEsports,
                                            contentDescription = "MAYA Replit Worker (App & 3D Game Studio)",
                                            tint = accentColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }

                            // When typing: Show single expandable '+' button so text field stays super wide
                            AnimatedVisibility(
                                visible = inputText.isNotEmpty(),
                                enter = fadeIn() + expandHorizontally(),
                                exit = fadeOut() + shrinkHorizontally()
                            ) {
                                var showAttachmentsMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(
                                        onClick = { showAttachmentsMenu = !showAttachmentsMenu },
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "More tools",
                                            tint = accentColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showAttachmentsMenu,
                                        onDismissRequest = { showAttachmentsMenu = false },
                                        modifier = Modifier.background(DarkSurface)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Attach Photo", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = accentColor) },
                                            onClick = {
                                                showAttachmentsMenu = false
                                                photoPickerLauncher.launch(
                                                    androidx.activity.result.PickVisualMediaRequest(
                                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                                    )
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("AI Media Studio", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = accentColor) },
                                            onClick = {
                                                showAttachmentsMenu = false
                                                isMediaStudioActive = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("3D Game & App Studio", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.SportsEsports, contentDescription = null, tint = accentColor) },
                                            onClick = {
                                                showAttachmentsMenu = false
                                                isAppGameStudioActive = true
                                            }
                                        )
                                    }
                                }
                            }

                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = { Text("Message ${character.displayName}...", color = TextMuted, fontSize = 15.sp) },
                                maxLines = 5,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { doSendMessage() }),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 15.sp,
                                    color = TextPrimary,
                                    lineHeight = 20.sp
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = DarkCard,
                                    unfocusedContainerColor = DarkCard,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                                    .testTag("message_input_field")
                            )

                            if (inputText.isNotBlank() || selectedBitmap != null) {
                                IconButton(
                                    onClick = { doSendMessage() },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(accentColor)
                                        .testTag("send_message_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        isVoiceCallActive = true
                                    },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .testTag("voice_input_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Start Voice Call",
                                        tint = accentColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isVoiceCallActive) {
            com.cocateail.maya.ui.components.VoiceCallDialog(
                character = character,
                accentColor = accentColor,
                onSendMessage = { spokenPrompt ->
                    onSendMessageDirect(spokenPrompt)
                },
                onDismiss = {
                    isVoiceCallActive = false
                }
            )
        }

        if (isMediaStudioActive) {
            com.cocateail.maya.ui.components.MediaStudioDialog(
                accentColor = accentColor,
                onDismiss = { isMediaStudioActive = false },
                onGenerateMedia = { mediaUrl, promptText, isVideo ->
                    val formattedMsg = if (isVideo) {
                        "🎬 **AI Generated Video**: $promptText"
                    } else {
                        "🎨 **AI Generated Image**: $promptText"
                    }
                    onSendMessage(formattedMsg, null, mediaUrl)
                }
            )
        }

        if (isAppGameStudioActive) {
            com.cocateail.maya.ui.components.AppGameStudioDialog(
                accentColor = accentColor,
                onDismiss = { isAppGameStudioActive = false },
                onSendToChat = { projectMsg ->
                    onSendMessage(projectMsg, null, null)
                }
            )
        }

        if (isTessaComputerDialogOpen) {
            com.cocateail.maya.ui.components.TessaComputerBrowserDialog(
                pairedComputer = null,
                activeTask = tessaTasks.firstOrNull(),
                uiStatus = tessaUiStatus,
                onDismiss = { isTessaComputerDialogOpen = false },
                onApprovePermission = onApproveTessaPermission,
                onDenyPermission = onDenyTessaPermission
            )
        }

        if (isTessaDiagnosticsDialogOpen) {
            com.cocateail.maya.ui.components.TessaDiagnosticsDialog(
                config = developerConfig,
                onDismiss = { isTessaDiagnosticsDialogOpen = false },
                onOpenDeveloperConfig = {
                    if (developerUnlocked) {
                        onOpenDeveloperPanel()
                    } else {
                        onOpenSettings()
                    }
                }
            )
        }
    }
}

@Composable
fun ThinkingIndicator(
    characterName: String,
    accentColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots_animation")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, delayMillis = 150), RepeatMode.Reverse),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, delayMillis = 300), RepeatMode.Reverse),
        label = "dot3"
    )

    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkCard)
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$characterName is thinking",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(accentColor.copy(alpha = dot1Alpha)))
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(accentColor.copy(alpha = dot2Alpha)))
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(accentColor.copy(alpha = dot3Alpha)))
        }
    }
}
