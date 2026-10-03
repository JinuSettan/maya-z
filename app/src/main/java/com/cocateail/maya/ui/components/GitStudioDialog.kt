package com.cocateail.maya.ui.components

import android.content.Context
import android.util.Base64
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cocateail.maya.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitStudioDialog(
    accentColor: Color,
    initialProject: GeneratedProject? = null,
    onDismiss: () -> Unit,
    onImportedProjectLoaded: (GeneratedProject) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("maya_git_prefs", Context.MODE_PRIVATE) }

    var activeTab by remember { mutableIntStateOf(0) } // 0: Push to GitHub, 1: Import from GitHub

    // GitHub Config States
    var githubToken by remember { mutableStateOf(prefs.getString("github_pat", "") ?: "") }
    var repositoryName by remember { mutableStateOf(initialProject?.title?.lowercase()?.replace(" ", "-")?.replace(":", "") ?: "maya-ai-app") }
    var existingRepoOwner by remember { mutableStateOf("") }
    var isNewRepoMode by remember { mutableStateOf(true) }
    var commitMessage by remember { mutableStateOf("🚀 Feat: Built with MAYA AI Studio") }

    // Import States
    var importRepoUrl by remember { mutableStateOf("") }
    var importBranch by remember { mutableStateOf("main") }

    // CI/CD Actions States
    var cicdRepo by remember { mutableStateOf("JinuSettan/maya-z") }
    var cicdBranch by remember { mutableStateOf("main") }
    var workflowRuns by remember { mutableStateOf<List<WorkflowRunInfo>>(emptyList()) }
    var workflowArtifacts by remember { mutableStateOf<List<WorkflowArtifactInfo>>(emptyList()) }
    var isCheckingRuns by remember { mutableStateOf(false) }

    // Status Log & Loader
    var isProcessing by remember { mutableStateOf(false) }
    var statusLog by remember { mutableStateOf("") }
    var pushedRepoUrl by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(24.dp)),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
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
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "MAYA GITHUB CI/CD",
                                color = accentColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "Git Push & Import Studio",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_git_studio_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mode Tabs (Push | Import | CI/CD Actions)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCard)
                        .padding(4.dp)
                ) {
                    listOf("🚀 Push", "📥 Import", "⚡ CI/CD APK").forEachIndexed { index, label ->
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
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Common Token Field
                Text("GitHub Personal Access Token (PAT)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = {
                        githubToken = it
                        prefs.edit().putString("github_pat", it.trim()).apply()
                    },
                    placeholder = { Text("ghp_xxxxxxxxxxxxxxxxxxxx", color = TextMuted, fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = DarkBorder,
                        focusedContainerColor = DarkCard,
                        unfocusedContainerColor = DarkCard,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = accentColor) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (activeTab == 0) {
                    // PUSH TO GITHUB SECTION
                    Text("Repository Settings", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = isNewRepoMode,
                            onClick = { isNewRepoMode = true },
                            label = { Text("Create New Repo") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentColor, selectedLabelColor = TextPrimary)
                        )
                        FilterChip(
                            selected = !isNewRepoMode,
                            onClick = { isNewRepoMode = false },
                            label = { Text("Existing Repo") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentColor, selectedLabelColor = TextPrimary)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isNewRepoMode) {
                        OutlinedTextField(
                            value = repositoryName,
                            onValueChange = { repositoryName = it },
                            label = { Text("Repository Name") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = DarkBorder,
                                focusedContainerColor = DarkCard,
                                unfocusedContainerColor = DarkCard,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                    } else {
                        OutlinedTextField(
                            value = existingRepoOwner,
                            onValueChange = { existingRepoOwner = it },
                            label = { Text("GitHub Owner/Repo (e.g. username/repo-name)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = DarkBorder,
                                focusedContainerColor = DarkCard,
                                unfocusedContainerColor = DarkCard,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Commit Message") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkCard,
                            unfocusedContainerColor = DarkCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (githubToken.isBlank()) {
                                Toast.makeText(context, "Please enter your GitHub Token!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (initialProject == null) {
                                Toast.makeText(context, "No project generated to push!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            isProcessing = true
                            statusLog = "Connecting to GitHub..."

                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val (repoUrl, log) = pushProjectToGitHubApi(
                                        token = githubToken.trim(),
                                        repoName = if (isNewRepoMode) repositoryName.trim() else existingRepoOwner.trim(),
                                        isNew = isNewRepoMode,
                                        project = initialProject,
                                        commitMsg = commitMessage
                                    )
                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                        statusLog = log
                                        pushedRepoUrl = repoUrl
                                        Toast.makeText(context, "🎉 Successfully pushed to GitHub!", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                        statusLog = "❌ Push Failed: ${e.localizedMessage}"
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pushing to GitHub...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Push Source Code to GitHub", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                } else if (activeTab == 1) {
                    // IMPORT FROM GITHUB SECTION
                    Text("Import Incomplete Project from GitHub", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = importRepoUrl,
                        onValueChange = { importRepoUrl = it },
                        placeholder = { Text("e.g. username/repository-name or https://github.com/owner/repo", color = TextMuted, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkCard,
                            unfocusedContainerColor = DarkCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = accentColor) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            if (importRepoUrl.isBlank()) {
                                Toast.makeText(context, "Please enter GitHub Repository URL or owner/repo!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            isProcessing = true
                            statusLog = "Downloading repository contents..."

                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val importedProj = importProjectFromGitHubApi(
                                        token = githubToken.trim(),
                                        repoPath = importRepoUrl.trim()
                                    )
                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                        statusLog = "✅ Successfully imported ${importedProj.files.size} files from GitHub!"
                                        onImportedProjectLoaded(importedProj)
                                        Toast.makeText(context, "Project Imported into MAYA Studio!", Toast.LENGTH_LONG).show()
                                        onDismiss()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                        statusLog = "❌ Import Failed: ${e.localizedMessage}"
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cloning Repository...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import Repository into MAYA", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // CI/CD APK BUILD SECTION (Tab 2)
                    Text("GitHub Actions APK Builder", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = cicdRepo,
                        onValueChange = { cicdRepo = it },
                        label = { Text("Repository (owner/repo)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkCard,
                            unfocusedContainerColor = DarkCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = cicdBranch,
                        onValueChange = { cicdBranch = it },
                        label = { Text("Branch (default: main)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkCard,
                            unfocusedContainerColor = DarkCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (githubToken.isBlank()) {
                                    Toast.makeText(context, "Please enter your GitHub Token!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isProcessing = true
                                statusLog = "Triggering GitHub Actions workflow (workflow_dispatch)..."

                                coroutineScope.launch(Dispatchers.IO) {
                                    val (success, msg) = triggerWorkflowDispatch(
                                        token = githubToken.trim(),
                                        repoPath = cicdRepo.trim(),
                                        ref = cicdBranch.trim()
                                    )
                                    val runs = fetchWorkflowRuns(githubToken.trim(), cicdRepo.trim())
                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                        statusLog = msg
                                        workflowRuns = runs
                                    }
                                }
                            },
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Trigger Build", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                isCheckingRuns = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    val runs = fetchWorkflowRuns(githubToken.trim(), cicdRepo.trim())
                                    val artifacts = if (runs.isNotEmpty()) {
                                        fetchWorkflowArtifacts(githubToken.trim(), cicdRepo.trim(), runs.first().id)
                                    } else emptyList()

                                    withContext(Dispatchers.Main) {
                                        isCheckingRuns = false
                                        workflowRuns = runs
                                        workflowArtifacts = artifacts
                                        if (runs.isNotEmpty()) {
                                            val latest = runs.first()
                                            statusLog = "Workflow: ${latest.name}\nStatus: ${latest.status} | Conclusion: ${latest.conclusion ?: "running..."}\nRuns found: ${runs.size}"
                                        } else {
                                            statusLog = "No recent workflow runs found. Click 'Trigger Build' to start!"
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = accentColor)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check Status", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (workflowRuns.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Recent Actions Runs:", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))

                        workflowRuns.take(3).forEach { run ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = DarkCard,
                                border = BorderStroke(1.dp, DarkBorder)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#${run.id}",
                                            color = accentColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = when {
                                                run.conclusion == "success" -> "✅ SUCCESS"
                                                run.conclusion == "failure" -> "❌ FAILED"
                                                run.status == "in_progress" -> "⏳ IN PROGRESS"
                                                run.status == "queued" -> "🕒 QUEUED"
                                                else -> run.status.uppercase()
                                            },
                                            color = when {
                                                run.conclusion == "success" -> Color(0xFF10B981)
                                                run.conclusion == "failure" -> Color(0xFFEF4444)
                                                else -> Color(0xFFF59E0B)
                                            },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = run.htmlUrl,
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    if (workflowArtifacts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Generated Artifacts:", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        workflowArtifacts.forEach { art ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF064E3B).copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, Color(0xFF10B981))
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(art.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("${art.sizeInBytes / (1024 * 1024)} MB", color = TextSecondary, fontSize = 10.sp)
                                    }
                                    Icon(Icons.Default.Download, contentDescription = "Download APK", tint = Color(0xFF10B981))
                                }
                            }
                        }
                    }
                }

                if (statusLog.isNotBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkBackground)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = statusLog,
                            color = if (statusLog.contains("❌")) Color(0xFFEF4444) else accentColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                pushedRepoUrl?.let { url ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "🌐 View on GitHub: $url",
                        color = accentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// REST API HELPER FOR GITHUB PUSH
private suspend fun pushProjectToGitHubApi(
    token: String,
    repoName: String,
    isNew: Boolean,
    project: GeneratedProject,
    commitMsg: String
): Pair<String, String> = withContext(Dispatchers.IO) {
    var ownerRepo = repoName.replace("https://github.com/", "").replace(".git", "").trim()

    // 1. If new repo mode, create the repository first
    if (isNew) {
        val createUrl = URL("https://api.github.com/user/repos")
        val conn = createUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "token $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.doOutput = true

        val jsonBody = JSONObject().apply {
            put("name", repoName)
            put("private", false)
            put("auto_init", true)
            put("description", "Built automatically with MAYA AI Studio")
        }

        conn.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

        val responseCode = conn.responseCode
        if (responseCode in 200..202) {
            val resString = conn.inputStream.bufferedReader().use { it.readText() }
            val resObj = JSONObject(resString)
            ownerRepo = resObj.getString("full_name")
        }
    }

    // 2. Commit files to the repository contents
    var uploadedCount = 0
    project.files.forEach { file ->
        try {
            val contentBase64 = Base64.encodeToString(file.content.toByteArray(), Base64.NO_WRAP)
            val putUrl = URL("https://api.github.com/repos/$ownerRepo/contents/${file.path}")

            val conn = putUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("message", commitMsg)
                put("content", contentBase64)
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode in 200..201) {
                uploadedCount++
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val finalRepoUrl = "https://github.com/$ownerRepo"
    val log = "✅ Successfully committed $uploadedCount files to $ownerRepo on GitHub!"
    return@withContext Pair(finalRepoUrl, log)
}

// REST API HELPER FOR GITHUB IMPORT
private suspend fun importProjectFromGitHubApi(
    token: String,
    repoPath: String
): GeneratedProject = withContext(Dispatchers.IO) {
    val cleanOwnerRepo = repoPath
        .replace("https://github.com/", "")
        .replace(".git", "")
        .trim('/')

    val apiUrl = URL("https://api.github.com/repos/$cleanOwnerRepo/contents")
    val conn = apiUrl.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    if (token.isNotBlank()) {
        conn.setRequestProperty("Authorization", "token $token")
    }
    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

    val responseCode = conn.responseCode
    if (responseCode !in 200..202) {
        throw Exception("GitHub API Error ($responseCode). Check token or repo name!")
    }

    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
    val jsonArray = JSONArray(responseText)

    val importedFiles = mutableListOf<ProjectFile>()
    var mainHtml = ""

    for (i in 0 until jsonArray.length()) {
        val item = jsonArray.getJSONObject(i)
        val name = item.getString("name")
        val path = item.getString("path")
        val downloadUrl = item.optString("download_url")

        if (downloadUrl.isNotBlank() && downloadUrl != "null") {
            try {
                val fileContent = URL(downloadUrl).readText()
                importedFiles.add(ProjectFile(name = name, path = path, content = fileContent))

                if (name == "index.html" || name.endsWith(".html")) {
                    mainHtml = fileContent
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (mainHtml.isBlank() && importedFiles.isNotEmpty()) {
        mainHtml = "<html><body style='background:#0F172A;color:#FFF;padding:20px;'><h2>Imported Project: $cleanOwnerRepo</h2><pre>${importedFiles.first().content}</pre></body></html>"
    }

    return@withContext GeneratedProject(
        title = cleanOwnerRepo.split("/").lastOrNull() ?: "Imported GitHub App",
        category = "GitHub Repository",
        files = importedFiles,
        mainHtmlContent = mainHtml
    )
}

data class WorkflowRunInfo(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val createdAt: String
)

data class WorkflowArtifactInfo(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val downloadUrl: String
)

// Trigger workflow_dispatch for build-maya-android.yml
private suspend fun triggerWorkflowDispatch(
    token: String,
    repoPath: String,
    ref: String = "main"
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    val cleanOwnerRepo = repoPath
        .replace("https://github.com/", "")
        .replace(".git", "")
        .trim('/')

    try {
        val apiUrl = URL("https://api.github.com/repos/$cleanOwnerRepo/actions/workflows/build-maya-android.yml/dispatches")
        val conn = apiUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "token $token")
        }
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("Content-Type", "application/json")

        val payload = JSONObject().apply {
            put("ref", ref)
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray())
        }

        val responseCode = conn.responseCode
        if (responseCode in 200..204) {
            Pair(true, "🚀 GitHub Actions APK Build triggered successfully on branch '$ref'!")
        } else {
            val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
            Pair(false, "❌ Workflow Trigger Failed ($responseCode): $err")
        }
    } catch (e: Exception) {
        Pair(false, "❌ Error triggering workflow: ${e.localizedMessage}")
    }
}

// Fetch Workflow Runs
private suspend fun fetchWorkflowRuns(
    token: String,
    repoPath: String
): List<WorkflowRunInfo> = withContext(Dispatchers.IO) {
    val cleanOwnerRepo = repoPath
        .replace("https://github.com/", "")
        .replace(".git", "")
        .trim('/')

    try {
        val apiUrl = URL("https://api.github.com/repos/$cleanOwnerRepo/actions/workflows/build-maya-android.yml/runs?per_page=5")
        val conn = apiUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "token $token")
        }
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

        val responseCode = conn.responseCode
        if (responseCode !in 200..204) {
            return@withContext emptyList()
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val runsArray = json.optJSONArray("workflow_runs") ?: return@withContext emptyList()

        val result = mutableListOf<WorkflowRunInfo>()
        for (i in 0 until runsArray.length()) {
            val runObj = runsArray.getJSONObject(i)
            result.add(
                WorkflowRunInfo(
                    id = runObj.getLong("id"),
                    name = runObj.optString("name", "Build MAYA Android APK"),
                    status = runObj.optString("status", "unknown"),
                    conclusion = if (runObj.isNull("conclusion")) null else runObj.getString("conclusion"),
                    htmlUrl = runObj.optString("html_url", ""),
                    createdAt = runObj.optString("created_at", "")
                )
            )
        }
        result
    } catch (e: Exception) {
        emptyList()
    }
}

// Fetch Artifacts for a run
private suspend fun fetchWorkflowArtifacts(
    token: String,
    repoPath: String,
    runId: Long
): List<WorkflowArtifactInfo> = withContext(Dispatchers.IO) {
    val cleanOwnerRepo = repoPath
        .replace("https://github.com/", "")
        .replace(".git", "")
        .trim('/')

    try {
        val apiUrl = URL("https://api.github.com/repos/$cleanOwnerRepo/actions/runs/$runId/artifacts")
        val conn = apiUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "token $token")
        }
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

        val responseCode = conn.responseCode
        if (responseCode !in 200..204) {
            return@withContext emptyList()
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val artifactsArray = json.optJSONArray("artifacts") ?: return@withContext emptyList()

        val result = mutableListOf<WorkflowArtifactInfo>()
        for (i in 0 until artifactsArray.length()) {
            val artObj = artifactsArray.getJSONObject(i)
            result.add(
                WorkflowArtifactInfo(
                    id = artObj.getLong("id"),
                    name = artObj.optString("name", "maya-android-apk"),
                    sizeInBytes = artObj.optLong("size_in_bytes", 0L),
                    downloadUrl = artObj.optString("archive_download_url", "")
                )
            )
        }
        result
    } catch (e: Exception) {
        emptyList()
    }
}
