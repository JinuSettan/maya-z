package com.cocateail.maya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cocateail.maya.ui.components.BrainSetupView
import com.cocateail.maya.ui.components.DeveloperPanelDialog
import com.cocateail.maya.ui.screens.ChatScreen
import com.cocateail.maya.ui.screens.SettingsScreen
import com.cocateail.maya.ui.theme.DarkBackground
import com.cocateail.maya.ui.theme.MayaTheme
import com.cocateail.maya.ui.viewmodel.MayaViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MayaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MayaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    val navController = rememberNavController()

                    val brainProgress by viewModel.brainProgress.collectAsState()
                    val brainReady by viewModel.brainReady.collectAsState()
                    val brainError by viewModel.brainError.collectAsState()

                    val conversations by viewModel.conversations.collectAsState()
                    val activeConvId by viewModel.activeConversationId.collectAsState()
                    val messages by viewModel.messages.collectAsState()
                    val character by viewModel.selectedCharacter.collectAsState()
                    val developerConfig by viewModel.developerConfig.collectAsState()
                    val developerUnlocked by viewModel.developerUnlocked.collectAsState()

                    val isThinking by viewModel.isThinking.collectAsState()
                    val isSpeaking by viewModel.isSpeaking.collectAsState()
                    val activeReplyToMessage by viewModel.activeReplyToMessage.collectAsState()
                    val tessaTasks by viewModel.tessaTasks.collectAsState()
                    val tessaUiStatus by viewModel.tessaUiStatus.collectAsState()

                    val showDeveloperPanel by viewModel.showDeveloperPanel.collectAsState()

                    if (!brainReady) {
                        BrainSetupView(
                            progress = brainProgress,
                            isReady = brainReady,
                            error = brainError,
                            onRetry = { viewModel.initializeBrain() },
                            onStart = { viewModel.initializeBrain() }
                        )
                    } else {
                        NavHost(navController = navController, startDestination = "chat") {
                            composable("chat") {
                                ChatScreen(
                                    conversations = conversations,
                                    activeConversationId = activeConvId,
                                    messages = messages,
                                    character = character,
                                    developerConfig = developerConfig,
                                    developerUnlocked = developerUnlocked,
                                    isThinking = isThinking,
                                    isSpeaking = isSpeaking,
                                    activeReplyToMessage = activeReplyToMessage,
                                    tessaTasks = tessaTasks,
                                    tessaUiStatus = tessaUiStatus,
                                    onSelectAssistantMode = { mode -> viewModel.selectAssistantMode(mode) },
                                    onApproveTessaPermission = { taskId, reusable ->
                                        viewModel.approveTessaPermission(taskId, reusable)
                                    },
                                    onDenyTessaPermission = { taskId -> viewModel.denyTessaPermission(taskId) },
                                    onCancelTessaTask = { taskId -> viewModel.cancelTessaTask(taskId) },
                                    onReplyToMessage = { msg -> viewModel.setReplyToMessage(msg) },
                                    onSendMessage = { text, bmp, url ->
                                        viewModel.sendMessage(text, bmp, url)
                                    },
                                    onSendMessageDirect = { text ->
                                        viewModel.sendMessageDirect(text)
                                    },
                                    onSelectConversation = { id -> viewModel.selectConversation(id) },
                                    onNewConversation = { viewModel.newConversation() },
                                    onDeleteConversation = { id -> viewModel.deleteConversation(id) },
                                    onPinMessage = { id, pinned -> viewModel.togglePinMessage(id, pinned) },
                                    onSpeakMessage = { text -> viewModel.speakMessage(text) },
                                    onOpenDeveloperPanel = { viewModel.openDeveloperPanel() },
                                    onOpenSettings = { navController.navigate("settings") }
                                )
                            }

                            composable("settings") {
                                SettingsScreen(
                                    selectedCharacter = character,
                                    developerUnlocked = developerUnlocked,
                                    onSelectCharacter = { char -> viewModel.selectCharacter(char) },
                                    onOpenDeveloperPanel = { viewModel.openDeveloperPanel() },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }

                        if (showDeveloperPanel && developerUnlocked) {
                            DeveloperPanelDialog(
                                config = developerConfig,
                                onDismiss = { viewModel.dismissDeveloperPanel() },
                                onSave = { newConfig -> viewModel.saveDeveloperConfig(newConfig) }
                            )
                        }
                    }
                }
            }
        }
    }
}
