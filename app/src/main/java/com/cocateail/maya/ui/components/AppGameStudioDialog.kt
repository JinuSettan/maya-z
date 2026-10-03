package com.cocateail.maya.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cocateail.maya.ui.theme.*
import com.cocateail.maya.util.MediaDownloadHelper
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class ProjectFile(
    val name: String,
    val path: String,
    val content: String,
    val isFolder: Boolean = false
)

data class GeneratedProject(
    val title: String,
    val category: String,
    val files: List<ProjectFile>,
    val mainHtmlContent: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGameStudioDialog(
    accentColor: Color,
    onDismiss: () -> Unit,
    onSendToChat: (String) -> Unit
) {
    val context = LocalContext.current
    var activeTab by remember { mutableIntStateOf(0) } // 0: Prompt & Templates, 1: Live Sandbox, 2: Code & Folder Tree

    var promptInput by remember { mutableStateOf("") }
    var isBuilding by remember { mutableStateOf(false) }
    var isGitStudioActive by remember { mutableStateOf(false) }

        // Currently active or generated project
    var currentProject by remember { mutableStateOf<GeneratedProject?>(getSample3DFootballProject()) }
    var selectedFile by remember { mutableStateOf<ProjectFile?>(currentProject?.files?.firstOrNull()) }

    val builderAgent = remember { com.cocateail.maya.studio.BuilderAgent(context) }
    var builderStatus by remember { mutableStateOf<com.cocateail.maya.studio.BuilderStatus?>(null) }
    var builderStatusText by remember { mutableStateOf("") }
    var builderIteration by remember { mutableIntStateOf(1) }
    val coroutineScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .clip(RoundedCornerShape(24.dp)),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Navigation Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = accentColor.copy(alpha = 0.2f),
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "MAYA AI REPLIT WORKER",
                                color = accentColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "App & 3D Game Studio",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { isGitStudioActive = true },
                            modifier = Modifier.testTag("open_git_studio_button")
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = "GitHub CI/CD Studio", tint = accentColor)
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("close_app_game_studio")
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = "Close", tint = TextPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Studio Mode Selector Tabs (Prompt/Build | Play Sandbox | Folder & Code)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCard)
                        .padding(4.dp)
                ) {
                    listOf("✨ Create Project", "🎮 Live Sandbox", "📁 File Tree & Code").forEachIndexed { index, label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (activeTab == index) accentColor else Color.Transparent)
                                .clickable { activeTab = index }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (activeTab == index) TextPrimary else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // TAB CONTENT
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (activeTab) {
                        0 -> CreateProjectTab(
                            accentColor = accentColor,
                            promptInput = promptInput,
                            onPromptChange = { promptInput = it },
                            isBuilding = isBuilding,
                            onBuildProject = { prompt ->
                                isBuilding = true
                                coroutineScope.launch {
                                    try {
                                        val projectType = when {
                                            prompt.lowercase().contains("photo") || prompt.lowercase().contains("edit") || prompt.lowercase().contains("image") || prompt.lowercase().contains("filter") ->
                                                com.cocateail.maya.studio.BuilderProjectType.PHOTO_EDITOR
                                            prompt.lowercase().contains("game") || prompt.lowercase().contains("3d") || prompt.lowercase().contains("physics") ->
                                                com.cocateail.maya.studio.BuilderProjectType.GAME_3D
                                            else ->
                                                com.cocateail.maya.studio.BuilderProjectType.WEB_APP
                                        }

                                        val newProject = builderAgent.executeTask(prompt, projectType) { status, text, iter ->
                                            builderStatus = status
                                            builderStatusText = text
                                            builderIteration = iter
                                        }

                                        currentProject = newProject
                                        selectedFile = newProject.files.firstOrNull()
                                        activeTab = 1 // Switch to Live Sandbox
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(context, "Builder Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isBuilding = false
                                        builderStatus = null
                                    }
                                }
                            },
                            onSelectPreset = { presetProject ->
                                currentProject = presetProject
                                selectedFile = presetProject.files.firstOrNull()
                                activeTab = 1 // Switch to Live Sandbox
                            }
                        )

                        1 -> LiveSandboxTab(
                            project = currentProject,
                            accentColor = accentColor,
                            onExportProject = { proj ->
                                exportProjectToDownloads(context, proj)
                            },
                            onPushToGit = {
                                isGitStudioActive = true
                            },
                            onShareToChat = { proj ->
                                onSendToChat("🚀 Check out my new AI Built 3D Game/App: **${proj.title}**!\n\nIncludes complete multi-file project source code (HTML5, Three.js 3D WebGL Engine, CSS3)!")
                                onDismiss()
                            }
                        )

                        2 -> FileTreeAndCodeTab(
                            project = currentProject,
                            selectedFile = selectedFile,
                            onSelectFile = { selectedFile = it },
                            accentColor = accentColor
                        )
                    }
                }
            }
        }

        if (isGitStudioActive) {
            GitStudioDialog(
                accentColor = accentColor,
                initialProject = currentProject,
                onDismiss = { isGitStudioActive = false },
                onImportedProjectLoaded = { importedProj ->
                    currentProject = importedProj
                    selectedFile = importedProj.files.firstOrNull()
                    activeTab = 1 // Switch to Live Sandbox
                }
            )
        }

        if (builderStatus != null) {
            Dialog(onDismissRequest = {}) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    color = DarkCard,
                    border = BorderStroke(1.dp, accentColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🤖 MAYA Autonomous Builder Agent",
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                            CircularProgressIndicator(
                                color = accentColor,
                                strokeWidth = 4.dp,
                                modifier = Modifier.fillMaxSize()
                            )
                            Text(
                                text = "${builderIteration}/5",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = builderStatus.toString(),
                            color = when (builderStatus) {
                                com.cocateail.maya.studio.BuilderStatus.SUCCESS -> Color.Green
                                com.cocateail.maya.studio.BuilderStatus.FAILED -> Color.Red
                                else -> accentColor
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            letterSpacing = 1.2.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = builderStatusText,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateProjectTab(
    accentColor: Color,
    promptInput: String,
    onPromptChange: (String) -> Unit,
    isBuilding: Boolean,
    onBuildProject: (String) -> Unit,
    onSelectPreset: (GeneratedProject) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("Describe the App or 3D Game you want to build:", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = promptInput,
            onValueChange = onPromptChange,
            placeholder = {
                Text(
                    "e.g., Create a 3D eFootball Penalty Shootout game with realistic physics, goalkeeper AI, and touch controls...",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .testTag("replit_worker_prompt_field"),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor,
                unfocusedBorderColor = DarkBorder,
                focusedContainerColor = DarkCard,
                unfocusedContainerColor = DarkCard,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val prompt = promptInput.ifBlank { "3D Space Galaxy Battle Game" }
                onBuildProject(prompt)
            },
            enabled = !isBuilding,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (isBuilding) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Building Folder Structure & Code...", fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Build Complete Project (Multi-File)", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text("⚡ Quick Replit Presets & Templates", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))

        val presets = listOf(
            Triple("📸 Pro Photo Editor & Filter Studio", "Real-time Canvas Filters, Doodle Brush & Export", getSamplePhotoEditorProject()),
            Triple("⚽ 3D eFootball Penalty Kick", "Full 3D Physics Goal Shootout", getSample3DFootballProject()),
            Triple("🔫 3D PUBG Mini Survival Arena", "3D Shooter with Touch Joystick Controls", getSamplePUBGShooterProject()),
            Triple("🏎️ 3D Highway Car Racing", "3D Endless Car Dodge Engine", getSample3DRacingProject()),
            Triple("🐦 Retro Flappy Jump Arcade", "Classic physics tap to fly & pipe dodging", getSampleFlappyGameProject()),
            Triple("🚀 3D Space Galaxy Shooter", "3D WebGL Space Battle Action Game", getSample3DSpaceShooterProject()),
            Triple("📱 Full-Stack E-Commerce App", "Responsive HTML5/JS E-Commerce Dashboard", getSampleWebECommerceProject())
        )

        presets.forEach { (title, subtitle, proj) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelectPreset(proj) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(subtitle, color = TextSecondary, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = accentColor)
                }
            }
        }
    }
}

@Composable
private fun LiveSandboxTab(
    project: GeneratedProject?,
    accentColor: Color,
    onExportProject: (GeneratedProject) -> Unit,
    onPushToGit: (GeneratedProject) -> Unit,
    onShareToChat: (GeneratedProject) -> Unit
) {
    if (project == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active project. Select or build a project to test live!", color = TextSecondary)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(project.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Sandbox: HTML5 + Three.js 3D Engine", color = accentColor, fontSize = 11.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onPushToGit(project) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "Push", tint = accentColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Push Git", color = accentColor, fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = { onExportProject(project) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Export", tint = accentColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export", color = accentColor, fontSize = 11.sp)
                }

                Button(
                    onClick = { onShareToChat(project) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Share", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // WebGL / Canvas Live Web Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkBackground)
                .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                view?.post {
                                    view.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                                    view.loadDataWithBaseURL("https://app.local/", project.mainHtmlContent, "text/html", "UTF-8", null)
                                }
                                return true // Gracefully handled renderer process crash
                            }
                        }
                        loadDataWithBaseURL("https://app.local/", project.mainHtmlContent, "text/html", "UTF-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun FileTreeAndCodeTab(
    project: GeneratedProject?,
    selectedFile: ProjectFile?,
    onSelectFile: (ProjectFile) -> Unit,
    accentColor: Color
) {
    val context = LocalContext.current
    if (project == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active project available.", color = TextSecondary)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("📁 Project Files Structure (${project.files.size} Files)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(6.dp))

        // File Explorer Chips / List
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkCard)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            project.files.forEach { file ->
                val isSelected = selectedFile?.path == file.path
                Surface(
                    color = if (isSelected) accentColor else DarkSurface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onSelectFile(file) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (file.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (isSelected) TextPrimary else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = file.name,
                            color = if (isSelected) TextPrimary else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Code Viewer Area
        selectedFile?.let { file ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📄 ${file.path}", color = accentColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Code", file.content))
                    Toast.makeText(context, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkBackground)
                    .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = file.content,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

private fun exportProjectToDownloads(context: Context, project: GeneratedProject) {
    try {
        val fileName = "${project.title.replace(" ", "_")}_project.html"
        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadDir, fileName)

        FileOutputStream(file).use { out ->
            out.write(project.mainHtmlContent.toByteArray())
        }

        Toast.makeText(context, "📦 Exported project to Downloads: $fileName", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Export error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

private fun generateProjectFromPrompt(prompt: String): GeneratedProject {
    val clean = prompt.lowercase().trim()
    val cleanTitle = prompt.take(35).trim()

    return when {
        clean.contains("photo") || clean.contains("edit") || clean.contains("image") || clean.contains("filter") || clean.contains("canvas") -> {
            getSamplePhotoEditorProject().copy(title = "Pro Photo Studio: $cleanTitle")
        }
        clean.contains("flappy") || clean.contains("bird") || clean.contains("jump") || clean.contains("runner") -> {
            getSampleFlappyGameProject().copy(title = "Arcade Game: $cleanTitle")
        }
        clean.contains("pubg") || clean.contains("shoot") || clean.contains("gun") || clean.contains("fps") || clean.contains("sniper") || clean.contains("survival") -> {
            getSamplePUBGShooterProject().copy(title = "3D Survival: $cleanTitle")
        }
        clean.contains("car") || clean.contains("racing") || clean.contains("speed") || clean.contains("drive") || clean.contains("highway") || clean.contains("drift") -> {
            getSample3DRacingProject().copy(title = "3D Racing: $cleanTitle")
        }
        clean.contains("space") || clean.contains("galaxy") || clean.contains("alien") || clean.contains("star") -> {
            getSample3DSpaceShooterProject().copy(title = "3D Galaxy: $cleanTitle")
        }
        clean.contains("football") || clean.contains("penalty") || clean.contains("soccer") || clean.contains("fifa") -> {
            getSample3DFootballProject().copy(title = "3D Football: $cleanTitle")
        }
        clean.contains("shop") || clean.contains("store") || clean.contains("cart") || clean.contains("ecommerce") -> {
            getSampleWebECommerceProject().copy(title = "Shop: $cleanTitle")
        }
        clean.contains("game") || clean.contains("3d") -> {
            getSample3DFootballProject().copy(title = "3D Game: $cleanTitle")
        }
        else -> {
            getSampleCustomDynamicApp(cleanTitle)
        }
    }
}

// SAMPLE PRESET PROJECTS
private fun getSample3DFootballProject(): GeneratedProject {
    val indexHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>3D eFootball Penalty Kick</title>
            <style>
                body { margin:0; overflow:hidden; background:#0F172A; font-family:sans-serif; user-select:none; }
                #ui { position:absolute; top:15px; left:15px; color:#38BDF8; font-weight:bold; font-size:18px; z-index:10; }
                #instructions { position:absolute; bottom:20px; width:100%; text-align:center; color:#FFF; font-size:14px; text-shadow:0 2px 4px rgba(0,0,0,0.8); }
                canvas { display:block; }
            </style>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
        </head>
        <body>
            <div id="ui">⚽ Score: <span id="score">0</span></div>
            <div id="instructions">🎯 Drag / Flick the ball towards the Goal!</div>

            <script>
                let scene = new THREE.Scene();
                scene.background = new THREE.Color(0x0a192f);

                let camera = new THREE.PerspectiveCamera(60, window.innerWidth/window.innerHeight, 0.1, 1000);
                camera.position.set(0, 1.8, 5);

                let renderer = new THREE.WebGLRenderer({ antialias: true });
                renderer.setSize(window.innerWidth, window.innerHeight);
                document.body.appendChild(renderer.domElement);

                // Grass Pitch
                let pitchGeo = new THREE.PlaneGeometry(30, 50);
                let pitchMat = new THREE.MeshBasicMaterial({ color: 0x15803d });
                let pitch = new THREE.Mesh(pitchGeo, pitchMat);
                pitch.rotation.x = -Math.PI / 2;
                scene.add(pitch);

                // Goal Post Frame
                let goalGeo = new THREE.BoxGeometry(6, 3, 0.2);
                let goalMat = new THREE.MeshBasicMaterial({ color: 0xffffff, wireframe: true });
                let goal = new THREE.Mesh(goalGeo, goalMat);
                goal.position.set(0, 1.5, -15);
                scene.add(goal);

                // Football
                let ballGeo = new THREE.SphereGeometry(0.3, 32, 32);
                let ballMat = new THREE.MeshBasicMaterial({ color: 0xfacc15 });
                let ball = new THREE.Mesh(ballGeo, ballMat);
                ball.position.set(0, 0.3, 3);
                scene.add(ball);

                let score = 0;
                let isMoving = false;
                let vx = 0, vy = 0, vz = 0;

                // Touch / Mouse controls
                let startY = 0;
                window.addEventListener('touchstart', e => { startY = e.touches[0].clientY; });
                window.addEventListener('touchend', e => {
                    if (!isMoving) {
                        let diffY = startY - e.changedTouches[0].clientY;
                        if (diffY > 30) {
                            isMoving = true;
                            vz = -0.3;
                            vy = 0.08;
                        }
                    }
                });

                function animate() {
                    requestAnimationFrame(animate);
                    if (isMoving) {
                        ball.position.z += vz;
                        ball.position.y += vy;
                        ball.rotation.x -= 0.1;

                        if (ball.position.z <= -15) {
                            score++;
                            document.getElementById('score').innerText = score;
                            ball.position.set(0, 0.3, 3);
                            isMoving = false;
                        }
                    }
                    renderer.render(scene, camera);
                }
                animate();
            </script>
        </body>
        </html>
    """.trimIndent()

    return GeneratedProject(
        title = "3D eFootball Penalty Kick",
        category = "3D Game Engine",
        files = listOf(
            ProjectFile("index.html", "index.html", indexHtml),
            ProjectFile("game.js", "js/game.js", "// Three.js Physics Engine & Controller\nconsole.log('3D eFootball Engine Loaded');"),
            ProjectFile("style.css", "css/style.css", "/* 3D Game Style Sheet */\nbody { margin: 0; background: #0F172A; }"),
            ProjectFile("manifest.json", "manifest.json", "{\n  \"name\": \"3D eFootball\",\n  \"short_name\": \"eFootball\"\n}")
        ),
        mainHtmlContent = indexHtml
    )
}

private fun getSamplePUBGShooterProject(): GeneratedProject {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>3D PUBG Survival Arena</title>
            <style>
                body { margin:0; overflow:hidden; background:#020617; color:#F8FAFC; font-family:sans-serif; }
                #hud { position:absolute; top:10px; left:10px; z-index:10; font-weight:bold; }
                #crosshair { position:absolute; top:50%; left:50%; transform:translate(-50%, -50%); color:#00FFCC; font-size:24px; pointer-events:none; }
            </style>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
        </head>
        <body>
            <div id="hud">🔫 Ammo: 30/30 | Enemies Defeated: <span id="score">0</span></div>
            <div id="crosshair">+</div>

            <script>
                let scene = new THREE.Scene();
                scene.background = new THREE.Color(0x090d16);

                let camera = new THREE.PerspectiveCamera(75, window.innerWidth/window.innerHeight, 0.1, 1000);
                camera.position.set(0, 1.6, 5);

                let renderer = new THREE.WebGLRenderer({ antialias: true });
                renderer.setSize(window.innerWidth, window.innerHeight);
                document.body.appendChild(renderer.domElement);

                // Ground Terrain
                let groundGeo = new THREE.PlaneGeometry(100, 100);
                let groundMat = new THREE.MeshBasicMaterial({ color: 0x334155 });
                let ground = new THREE.Mesh(groundGeo, groundMat);
                ground.rotation.x = -Math.PI / 2;
                scene.add(ground);

                // Enemy Targets
                let enemies = [];
                for(let i=0; i<5; i++) {
                    let enemyGeo = new THREE.BoxGeometry(1, 2, 1);
                    let enemyMat = new THREE.MeshBasicMaterial({ color: 0xef4444 });
                    let enemy = new THREE.Mesh(enemyGeo, enemyMat);
                    enemy.position.set((Math.random()-0.5)*20, 1, -10 - Math.random()*20);
                    scene.add(enemy);
                    enemies.push(enemy);
                }

                function animate() {
                    requestAnimationFrame(animate);
                    enemies.forEach(e => { e.rotation.y += 0.02; });
                    renderer.render(scene, camera);
                }
                animate();
            </script>
        </body>
        </html>
    """.trimIndent()

    return GeneratedProject(
        title = "3D PUBG Mini Survival Arena",
        category = "3D FPS Shooter",
        files = listOf(
            ProjectFile("index.html", "index.html", html),
            ProjectFile("shooter.js", "js/shooter.js", "// Weapon System & AI Enemies"),
            ProjectFile("style.css", "css/style.css", "body { margin: 0; }")
        ),
        mainHtmlContent = html
    )
}

private fun getSample3DSpaceShooterProject(): GeneratedProject {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>3D Space Galaxy Battle</title>
            <style>
                body { margin:0; background:#000; overflow:hidden; font-family:sans-serif; }
                #scoreBoard { position:absolute; top:15px; left:15px; color:#38BDF8; font-size:18px; font-weight:bold; }
            </style>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
        </head>
        <body>
            <div id="scoreBoard">🚀 Score: <span id="sc">0</span></div>
            <script>
                let scene = new THREE.Scene();
                let camera = new THREE.PerspectiveCamera(75, window.innerWidth/window.innerHeight, 0.1, 1000);
                camera.position.z = 5;

                let renderer = new THREE.WebGLRenderer({ antialias: true });
                renderer.setSize(window.innerWidth, window.innerHeight);
                document.body.appendChild(renderer.domElement);

                // Spaceship
                let shipGeo = new THREE.ConeGeometry(0.5, 1.5, 4);
                let shipMat = new THREE.MeshBasicMaterial({ color: 0x38bdf8, wireframe: true });
                let ship = new THREE.Mesh(shipGeo, shipMat);
                ship.rotation.x = Math.PI/2;
                scene.add(ship);

                function animate() {
                    requestAnimationFrame(animate);
                    ship.rotation.z += 0.01;
                    renderer.render(scene, camera);
                }
                animate();
            </script>
        </body>
        </html>
    """.trimIndent()

    return GeneratedProject(
        title = "3D Space Galaxy Battle",
        category = "3D Sci-Fi Game",
        files = listOf(
            ProjectFile("index.html", "index.html", html),
            ProjectFile("space.js", "js/space.js", "// Starfield & Laser Cannons")
        ),
        mainHtmlContent = html
    )
}

private fun getSample3DRacingProject(): GeneratedProject {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>3D Highway Racing</title>
            <style>body { margin:0; background:#111; overflow:hidden; color:#FFF; font-family:sans-serif; }</style>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
        </head>
        <body>
            <div style="position:absolute; top:15px; left:15px; font-weight:bold; font-size:18px; color:#FACC15;">🏎️ Speed: 180 KM/H</div>
            <script>
                let scene = new THREE.Scene();
                let camera = new THREE.PerspectiveCamera(60, window.innerWidth/window.innerHeight, 0.1, 1000);
                camera.position.set(0, 3, 6); camera.lookAt(0,0,0);

                let renderer = new THREE.WebGLRenderer({ antialias: true });
                renderer.setSize(window.innerWidth, window.innerHeight);
                document.body.appendChild(renderer.domElement);

                let carGeo = new THREE.BoxGeometry(1.2, 0.6, 2.5);
                let carMat = new THREE.MeshBasicMaterial({ color: 0xef4444 });
                let car = new THREE.Mesh(carGeo, carMat);
                scene.add(car);

                function animate() {
                    requestAnimationFrame(animate);
                    renderer.render(scene, camera);
                }
                animate();
            </script>
        </body>
        </html>
    """.trimIndent()

    return GeneratedProject(
        title = "3D Highway Car Racing",
        category = "3D Racing Engine",
        files = listOf(
            ProjectFile("index.html", "index.html", html),
            ProjectFile("car.js", "js/car.js", "// Car Steering Physics")
        ),
        mainHtmlContent = html
    )
}

private fun getSampleWebECommerceProject(): GeneratedProject {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>MAYA Store</title>
            <style>
                body { margin:0; font-family:sans-serif; background:#0F172A; color:#F8FAFC; padding:20px; }
                .card { background:#1E293B; border-radius:16px; padding:16px; margin-bottom:12px; border:1px solid #334155; }
                .btn { background:#38BDF8; color:#0F172A; font-weight:bold; padding:10px 16px; border-radius:10px; border:none; cursor:pointer; }
            </style>
        </head>
        <body>
            <h2>🛍️ MAYA AI Store</h2>
            <div class="card">
                <h3>Futuristic AI Smartwatch</h3>
                <p>₹14,999 • Holographic Display, Neural Health Sync</p>
                <button class="btn" onclick="alert('Order Placed Successfully!')">Buy Now</button>
            </div>
        </body>
        </html>
    """.trimIndent()

    return GeneratedProject(
        title = "Full-Stack E-Commerce App",
        category = "Web & Mobile App",
        files = listOf(
            ProjectFile("index.html", "index.html", html),
            ProjectFile("app.js", "js/app.js", "// Cart & Checkout Payment Gateway Logic")
        ),
        mainHtmlContent = html
    )
}

private fun getSamplePhotoEditorProject(): GeneratedProject {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>MAYA Pro Photo Studio</title>
            <style>
                * { box-sizing: border-box; }
                body { margin:0; background:#0B0F19; color:#F8FAFC; font-family:-apple-system,BlinkMacSystemFont,sans-serif; display:flex; flex-direction:column; height:100vh; overflow:hidden; user-select:none; }
                header { padding:10px 16px; background:#111827; border-bottom:1px solid #1F2937; display:flex; justify-content:space-between; align-items:center; }
                header h1 { margin:0; font-size:15px; font-weight:700; color:#38BDF8; }
                .btn-save { background:#38BDF8; color:#0F172A; border:none; padding:6px 14px; border-radius:8px; font-weight:bold; font-size:12px; cursor:pointer; }
                #canvas-container { flex:1; display:flex; align-items:center; justify-content:center; padding:10px; background:#030712; overflow:hidden; }
                canvas { max-width:100%; max-height:100%; border-radius:12px; box-shadow:0 8px 24px rgba(0,0,0,0.6); touch-action:none; }
                #controls { background:#111827; border-top:1px solid #1F2937; padding:12px 14px; max-height:220px; overflow-y:auto; }
                .filter-presets { display:flex; gap:8px; overflow-x:auto; padding-bottom:8px; margin-bottom:10px; }
                .chip { background:#1F2937; color:#E2E8F0; border:1px solid #374151; padding:6px 12px; border-radius:20px; font-size:12px; white-space:nowrap; cursor:pointer; }
                .chip.active { background:#38BDF8; color:#0F172A; font-weight:bold; border-color:#38BDF8; }
                .slider-group { display:grid; grid-template-columns: 1fr 1fr; gap:8px; font-size:11px; }
                .slider-item { display:flex; flex-direction:column; }
                .slider-item label { color:#94A3B8; margin-bottom:2px; }
                input[type=range] { accent-color:#38BDF8; width:100%; }
                .draw-toolbar { display:flex; gap:8px; align-items:center; margin-top:8px; padding-top:8px; border-top:1px solid #1F2937; }
                .draw-toolbar label { font-size:11px; color:#94A3B8; }
            </style>
        </head>
        <body>
            <header>
                <h1>📸 MAYA Photo Studio</h1>
                <div>
                    <button class="btn-save" onclick="resetFilters()">Reset</button>
                    <button class="btn-save" style="background:#22C55E; color:#FFF; margin-left:6px;" onclick="downloadImage()">💾 Save</button>
                </div>
            </header>

            <div id="canvas-container">
                <canvas id="photoCanvas" width="400" height="400"></canvas>
            </div>

            <div id="controls">
                <div class="filter-presets">
                    <span class="chip active" onclick="applyPreset('normal', this)">Original</span>
                    <span class="chip" onclick="applyPreset('vintage', this)">Vintage</span>
                    <span class="chip" onclick="applyPreset('cyberpunk', this)">Cyberpunk</span>
                    <span class="chip" onclick="applyPreset('noir', this)">Noir (B&W)</span>
                    <span class="chip" onclick="applyPreset('radiant', this)">Warm Sun</span>
                    <span class="chip" onclick="applyPreset('cold', this)">Cool Teal</span>
                </div>

                <div class="slider-group">
                    <div class="slider-item">
                        <label>Brightness: <span id="val-bright">100%</span></label>
                        <input type="range" id="bright" min="20" max="200" value="100" oninput="renderPhoto()">
                    </div>
                    <div class="slider-item">
                        <label>Contrast: <span id="val-contrast">100%</span></label>
                        <input type="range" id="contrast" min="20" max="200" value="100" oninput="renderPhoto()">
                    </div>
                    <div class="slider-item">
                        <label>Saturation: <span id="val-sat">100%</span></label>
                        <input type="range" id="sat" min="0" max="200" value="100" oninput="renderPhoto()">
                    </div>
                    <div class="slider-item">
                        <label>Blur: <span id="val-blur">0px</span></label>
                        <input type="range" id="blur" min="0" max="10" value="0" oninput="renderPhoto()">
                    </div>
                </div>

                <div class="draw-toolbar">
                    <label>🎨 Brush:</label>
                    <input type="color" id="brushColor" value="#38BDF8">
                    <input type="range" id="brushSize" min="2" max="20" value="5" style="width:70px;">
                    <span style="font-size:11px; color:#94A3B8; margin-left:auto;">Touch to Draw</span>
                </div>
            </div>

            <script>
                const canvas = document.getElementById('photoCanvas');
                const ctx = canvas.getContext('2d');
                let baseImage = new Image();

                function initDefaultArtwork() {
                    const grad = ctx.createLinearGradient(0, 0, canvas.width, canvas.height);
                    grad.addColorStop(0, '#1E1B4B');
                    grad.addColorStop(0.5, '#4338CA');
                    grad.addColorStop(1, '#065F46');
                    ctx.fillStyle = grad;
                    ctx.fillRect(0, 0, canvas.width, canvas.height);

                    ctx.fillStyle = '#F43F5E';
                    ctx.beginPath();
                    ctx.arc(200, 160, 60, 0, Math.PI * 2);
                    ctx.fill();

                    ctx.fillStyle = '#FBBF24';
                    ctx.beginPath();
                    ctx.arc(230, 130, 20, 0, Math.PI * 2);
                    ctx.fill();

                    ctx.fillStyle = '#FFFFFF';
                    ctx.font = 'bold 22px sans-serif';
                    ctx.textAlign = 'center';
                    ctx.fillText('MAYA Pro Studio', 200, 280);
                    ctx.font = '14px sans-serif';
                    ctx.fillStyle = '#94A3B8';
                    ctx.fillText('Adjust filters & paint below!', 200, 310);

                    baseImage.src = canvas.toDataURL();
                }

                initDefaultArtwork();

                function renderPhoto() {
                    const b = document.getElementById('bright').value;
                    const c = document.getElementById('contrast').value;
                    const s = document.getElementById('sat').value;
                    const bl = document.getElementById('blur').value;

                    document.getElementById('val-bright').innerText = b + '%';
                    document.getElementById('val-contrast').innerText = c + '%';
                    document.getElementById('val-sat').innerText = s + '%';
                    document.getElementById('val-blur').innerText = bl + 'px';

                    ctx.filter = 'brightness(' + b + '%) contrast(' + c + '%) saturate(' + s + '%) blur(' + bl + 'px)';
                    ctx.clearRect(0, 0, canvas.width, canvas.height);
                    ctx.drawImage(baseImage, 0, 0, canvas.width, canvas.height);
                }

                function applyPreset(name, el) {
                    document.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
                    if(el) el.classList.add('active');

                    if (name === 'normal') { setFilters(100, 100, 100, 0); }
                    else if (name === 'vintage') { setFilters(110, 90, 80, 0); }
                    else if (name === 'cyberpunk') { setFilters(120, 140, 180, 0); }
                    else if (name === 'noir') { setFilters(110, 150, 0, 0); }
                    else if (name === 'radiant') { setFilters(115, 110, 140, 0); }
                    else if (name === 'cold') { setFilters(95, 120, 110, 0); }
                    renderPhoto();
                }

                function setFilters(b, c, s, bl) {
                    document.getElementById('bright').value = b;
                    document.getElementById('contrast').value = c;
                    document.getElementById('sat').value = s;
                    document.getElementById('blur').value = bl;
                }

                function resetFilters() {
                    applyPreset('normal', document.querySelector('.chip'));
                }

                let isDrawing = false;
                function getPos(e) {
                    const rect = canvas.getBoundingClientRect();
                    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
                    const clientY = e.touches ? e.touches[0].clientY : e.clientY;
                    return {
                        x: (clientX - rect.left) * (canvas.width / rect.width),
                        y: (clientY - rect.top) * (canvas.height / rect.height)
                    };
                }

                function startDraw(e) {
                    isDrawing = true;
                    const p = getPos(e);
                    ctx.beginPath();
                    ctx.moveTo(p.x, p.y);
                }
                function draw(e) {
                    if (!isDrawing) return;
                    e.preventDefault();
                    const p = getPos(e);
                    ctx.lineTo(p.x, p.y);
                    ctx.strokeStyle = document.getElementById('brushColor').value;
                    ctx.lineWidth = document.getElementById('brushSize').value;
                    ctx.lineCap = 'round';
                    ctx.stroke();
                }
                function stopDraw() {
                    if (isDrawing) {
                        isDrawing = false;
                        baseImage.src = canvas.toDataURL();
                    }
                }

                canvas.addEventListener('mousedown', startDraw);
                canvas.addEventListener('mousemove', draw);
                canvas.addEventListener('mouseup', stopDraw);
                canvas.addEventListener('touchstart', startDraw);
                canvas.addEventListener('touchmove', draw);
                canvas.addEventListener('touchend', stopDraw);

                function downloadImage() {
                    const link = document.createElement('a');
                    link.download = 'maya_edited_photo.png';
                    link.href = canvas.toDataURL();
                    link.click();
                    alert('Photo Saved! 🎨✨');
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    return GeneratedProject(
        title = "Pro Photo Editor & Filter Studio",
        category = "HTML5 Canvas App",
        files = listOf(
            ProjectFile("index.html", "index.html", html),
            ProjectFile("editor.js", "js/editor.js", "// Real-time Canvas Filter Pipeline\nconsole.log('MAYA Photo Studio Loaded');"),
            ProjectFile("filters.css", "css/filters.css", "/* Photo Studio UI Styles */\nbody { background: #0B0F19; }"),
            ProjectFile("manifest.json", "manifest.json", "{\n  \"name\": \"MAYA Photo Studio\",\n  \"short_name\": \"PhotoStudio\"\n}")
        ),
        mainHtmlContent = html
    )
}

private fun getSampleFlappyGameProject(): GeneratedProject {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <title>Retro Flappy Jump</title>
            <style>
                body { margin:0; overflow:hidden; background:#70c5ce; font-family:sans-serif; touch-action:none; }
                canvas { display:block; margin:auto; }
            </style>
        </head>
        <body>
            <canvas id="c"></canvas>
            <script>
                const c = document.getElementById('c');
                const ctx = c.getContext('2d');
                c.width = window.innerWidth;
                c.height = window.innerHeight;

                let bird = { x: 50, y: c.height/2, v: 0, g: 0.35, jump: -6.5, r: 14 };
                let pipes = [];
                let score = 0, best = 0;
                let over = false;

                function spawnPipe() {
                    let gap = 140;
                    let top = 40 + Math.random() * (c.height - gap - 120);
                    pipes.push({ x: c.width, top: top, bottom: top + gap, w: 50, passed: false });
                }

                function jump() {
                    if (over) {
                        bird.y = c.height/2; bird.v = 0; pipes = []; score = 0; over = false;
                    } else {
                        bird.v = bird.jump;
                    }
                }
                window.addEventListener('touchstart', (e) => { e.preventDefault(); jump(); });
                window.addEventListener('mousedown', jump);

                let frame = 0;
                function loop() {
                    requestAnimationFrame(loop);
                    ctx.fillStyle = '#70c5ce';
                    ctx.fillRect(0, 0, c.width, c.height);

                    // Ground
                    ctx.fillStyle = '#ded895';
                    ctx.fillRect(0, c.height - 40, c.width, 40);

                    if (!over) {
                        frame++;
                        if (frame % 100 === 0) spawnPipe();

                        bird.v += bird.g;
                        bird.y += bird.v;

                        if (bird.y + bird.r > c.height - 40) over = true;
                    }

                    ctx.fillStyle = '#73bf2e';
                    pipes.forEach(p => {
                        if (!over) p.x -= 2.2;
                        ctx.fillRect(p.x, 0, p.w, p.top);
                        ctx.fillRect(p.x, p.bottom, p.w, c.height - p.bottom);

                        if (!p.passed && p.x + p.w < bird.x) {
                            p.passed = true;
                            score++;
                            if (score > best) best = score;
                        }
                        if (bird.x + bird.r > p.x && bird.x - bird.r < p.x + p.w) {
                            if (bird.y - bird.r < p.top || bird.y + bird.r > p.bottom) {
                                over = true;
                            }
                        }
                    });

                    ctx.fillStyle = '#facc15';
                    ctx.beginPath();
                    ctx.arc(bird.x, bird.y, bird.r, 0, Math.PI*2);
                    ctx.fill();
                    ctx.fillStyle = '#000';
                    ctx.beginPath();
                    ctx.arc(bird.x + 6, bird.y - 4, 3, 0, Math.PI*2);
                    ctx.fill();

                    ctx.fillStyle = '#FFF';
                    ctx.font = 'bold 24px sans-serif';
                    ctx.textAlign = 'center';
                    ctx.fillText('Score: ' + score + ' (Best: ' + best + ')', c.width/2, 40);

                    if (over) {
                        ctx.fillStyle = 'rgba(0,0,0,0.6)';
                        ctx.fillRect(0, 0, c.width, c.height);
                        ctx.fillStyle = '#FFF';
                        ctx.font = 'bold 28px sans-serif';
                        ctx.fillText('GAME OVER', c.width/2, c.height/2 - 20);
                        ctx.font = '16px sans-serif';
                        ctx.fillText('Tap Screen to Play Again', c.width/2, c.height/2 + 25);
                    }
                }
                loop();
            </script>
        </body>
        </html>
    """.trimIndent()

    return GeneratedProject(
        title = "Retro Flappy Jump Arcade",
        category = "2D Arcade Game",
        files = listOf(
            ProjectFile("index.html", "index.html", html),
            ProjectFile("flappy.js", "js/flappy.js", "// Physics Jump & Collision Loop"),
            ProjectFile("arcade.css", "css/arcade.css", "body { margin: 0; }")
        ),
        mainHtmlContent = html
    )
}

private fun getSampleCustomDynamicApp(title: String): GeneratedProject {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>$title</title>
            <style>
                * { box-sizing:border-box; margin:0; padding:0; }
                body { background:#0B0F19; color:#F8FAFC; font-family:-apple-system,BlinkMacSystemFont,sans-serif; padding:16px; min-height:100vh; }
                header { padding:14px 0; border-bottom:1px solid #1F2937; margin-bottom:16px; display:flex; justify-content:space-between; align-items:center; }
                header h1 { font-size:18px; color:#38BDF8; font-weight:700; }
                .badge { background:rgba(56,189,248,0.15); color:#38BDF8; padding:4px 10px; border-radius:12px; font-size:11px; font-weight:bold; }
                .input-row { display:flex; gap:8px; margin-bottom:16px; }
                input[type=text] { flex:1; background:#1E293B; border:1px solid #334155; border-radius:12px; padding:10px 14px; color:#FFF; font-size:14px; outline:none; }
                button.add-btn { background:#38BDF8; color:#0F172A; border:none; padding:10px 18px; border-radius:12px; font-weight:bold; cursor:pointer; }
                .card { background:#1E293B; border:1px solid #334155; border-radius:14px; padding:14px; margin-bottom:10px; display:flex; justify-content:space-between; align-items:center; }
                .card.done { opacity:0.6; text-decoration:line-through; }
                .card-title { font-size:14px; font-weight:600; color:#F8FAFC; cursor:pointer; }
                .delete-btn { background:#EF4444; color:#FFF; border:none; border-radius:8px; padding:6px 10px; font-size:12px; cursor:pointer; }
                .stats { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-top:20px; }
                .stat-box { background:#111827; border:1px solid #1F2937; border-radius:12px; padding:12px; text-align:center; }
                .stat-box h4 { font-size:12px; color:#94A3B8; margin-bottom:4px; }
                .stat-box p { font-size:20px; color:#38BDF8; font-weight:bold; }
            </style>
        </head>
        <body>
            <header>
                <h1>✨ $title</h1>
                <span class="badge">MAYA Full-Stack</span>
            </header>

            <div class="input-row">
                <input type="text" id="itemInput" placeholder="Add new task or record..." onkeydown="if(event.key==='Enter')addItem()">
                <button class="add-btn" onclick="addItem()">+ Add</button>
            </div>

            <div id="listContainer"></div>

            <div class="stats">
                <div class="stat-box">
                    <h4>Total Items</h4>
                    <p id="totalCount">0</p>
                </div>
                <div class="stat-box">
                    <h4>Completed</h4>
                    <p id="doneCount">0</p>
                </div>
            </div>

            <script>
                let items = JSON.parse(localStorage.getItem('maya_app_items') || '["Explore Features", "Connect GitHub CI/CD", "Deploy Project"]');

                function render() {
                    const c = document.getElementById('listContainer');
                    c.innerHTML = '';
                    let completed = 0;

                    items.forEach((item, idx) => {
                        const isDone = typeof item === 'object' ? item.done : false;
                        const text = typeof item === 'object' ? item.text : item;
                        if(isDone) completed++;

                        const div = document.createElement('div');
                        div.className = 'card' + (isDone ? ' done' : '');
                        div.innerHTML = '<span class="card-title" onclick="toggleItem(' + idx + ')">' + (isDone ? '✅ ' : '⏳ ') + text + '</span><button class="delete-btn" onclick="deleteItem(' + idx + ')">Delete</button>';
                        c.appendChild(div);
                    });

                    document.getElementById('totalCount').innerText = items.length;
                    document.getElementById('doneCount').innerText = completed;
                    localStorage.setItem('maya_app_items', JSON.stringify(items));
                }

                function addItem() {
                    const input = document.getElementById('itemInput');
                    const text = input.value.trim();
                    if(text) {
                        items.unshift({ text: text, done: false });
                        input.value = '';
                        render();
                    }
                }

                function toggleItem(idx) {
                    if(typeof items[idx] === 'string') items[idx] = { text: items[idx], done: true };
                    else items[idx].done = !items[idx].done;
                    render();
                }

                function deleteItem(idx) {
                    items.splice(idx, 1);
                    render();
                }

                render();
            </script>
        </body>
        </html>
    """.trimIndent()

    return GeneratedProject(
        title = title,
        category = "Interactive Web/Mobile App",
        files = listOf(
            ProjectFile("index.html", "index.html", html),
            ProjectFile("app.js", "js/app.js", "// State Management & LocalStorage CRUD\nconsole.log('$title Initialized');"),
            ProjectFile("style.css", "css/style.css", "/* Modern Dark UI */\nbody { background: #0B0F19; color: #F8FAFC; }"),
            ProjectFile("manifest.json", "manifest.json", "{\n  \"name\": \"$title\",\n  \"short_name\": \"App\"\n}")
        ),
        mainHtmlContent = html
    )
}
