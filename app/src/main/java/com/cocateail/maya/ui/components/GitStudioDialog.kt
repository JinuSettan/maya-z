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
    val devPrefs = remember { context.getSharedPreferences("maya_prefs", Context.MODE_PRIVATE) }
    val githubExecutor = remember { com.cocateail.maya.studio.GitHubActionsBuildExecutor(context) }

    var githubToken by remember {
        val patFromGit = prefs.getString("github_pat", "")?.trim() ?: ""
        val patFromDev = try {
            val raw = devPrefs.getString("dev_config", null)
            if (raw != null) JSONObject(raw).optString("githubToken", "").trim() else ""
        } catch (_: Exception) { "" }
        mutableStateOf(patFromGit.ifBlank { patFromDev })
    }
    var repositoryName by remember { mutableStateOf(initialProject?.title?.lowercase()?.replace(" ", "-")?.replace(":", "") ?: "maya-ai-app") }
    var existingRepoOwner by remember {
        val fromDev = try {
            val raw = devPrefs.getString("dev_config", null)
            if (raw != null) JSONObject(raw).optString("githubRepository", "").trim() else ""
        } catch (_: Exception) { "" }
        mutableStateOf(fromDev)
    }
    var isNewRepoMode by remember { mutableStateOf(existingRepoOwner.isBlank()) }
    var isPrivateRepo by remember {
        val privFromDev = try {
            val raw = devPrefs.getString("dev_config", null)
            if (raw != null) JSONObject(raw).optBoolean("isCustomerRepoPrivate", false) else false
        } catch (_: Exception) { false }
        mutableStateOf(privFromDev)
    }
    var commitMessage by remember { mutableStateOf("🚀 Feat: Built with MAYA AI Studio") }
    var authenticatedUserLogin by remember { mutableStateOf<String?>(null) }

    // Import States
    var importRepoUrl by remember { mutableStateOf("") }
    var importBranch by remember { mutableStateOf("main") }

    // CI/CD Actions & Dual Build States (NO hardcoded fallback to JinuSettan/maya-z)
    var cicdRepo by remember {
        val fromDev = try {
            val raw = devPrefs.getString("dev_config", null)
            if (raw != null) JSONObject(raw).optString("githubRepository", "").trim() else ""
        } catch (_: Exception) { "" }
        mutableStateOf(fromDev)
    }
    var cicdBranch by remember {
        val fromDev = try {
            val raw = devPrefs.getString("dev_config", null)
            if (raw != null) JSONObject(raw).optString("githubBranch", "").trim() else ""
        } catch (_: Exception) { "" }
        mutableStateOf(fromDev.ifBlank { "main" })
    }
    var latestPushedCommitSha by remember {
        val shaFromDev = try {
            val raw = devPrefs.getString("dev_config", null)
            if (raw != null) JSONObject(raw).optString("lastPushedCommitSha", "").trim() else ""
        } catch (_: Exception) { "" }
        mutableStateOf(shaFromDev)
    }
    var selectedBackendMode by remember { mutableStateOf("GITHUB_ACTIONS") } // GITHUB_ACTIONS, CLOUD_RUN, AUTO
    var selectedArtifactType by remember { mutableStateOf("APK") } // APK, AAB, WEB
    var pollUntilComplete by remember { mutableStateOf(true) }
    var targetRunIdInput by remember { mutableStateOf("") }
    var cloudRunUrlInput by remember {
        val rawCfg = devPrefs.getString("dev_config", null)
        val initialUrl = try {
            if (rawCfg != null) JSONObject(rawCfg).optString("cloudRunBuildUrl", "").ifBlank {
                JSONObject(rawCfg).optString("remoteBuildServerUrl", "")
            } else ""
        } catch (_: Exception) { "" }
        mutableStateOf(initialUrl)
    }
    var currentBuildStatusLabel by remember { mutableStateOf("IDLE") } // Queued, Running, Success, Failed
    var latestBuildResult by remember { mutableStateOf<com.cocateail.maya.studio.BuildResult?>(com.cocateail.maya.studio.BuilderAgent.lastBuildResult) }
    var workflowRuns by remember { mutableStateOf<List<WorkflowRunInfo>>(emptyList()) }
    var workflowArtifacts by remember { mutableStateOf<List<WorkflowArtifactInfo>>(emptyList()) }
    var verifiedApkReport by remember { mutableStateOf<com.cocateail.maya.studio.ApkVerificationReport?>(null) }
    var verifiedAabReport by remember { mutableStateOf<com.cocateail.maya.studio.AabVerificationReport?>(null) }
    var isDownloadingArtifactId by remember { mutableStateOf<Long?>(null) }
    var isCheckingRuns by remember { mutableStateOf(false) }

    // Status Log & Loader
    var isProcessing by remember { mutableStateOf(false) }
    var statusLog by remember { mutableStateOf("") }
    var pushedRepoUrl by remember { mutableStateOf<String?>(null) }

    fun persistGitHubSettings(
        newToken: String = githubToken,
        newRepo: String = cicdRepo,
        newBranch: String = cicdBranch,
        newPrivate: Boolean = isPrivateRepo,
        newCommitSha: String = latestPushedCommitSha
    ) {
        prefs.edit().putString("github_pat", newToken.trim()).apply()
        try {
            val existingRaw = devPrefs.getString("dev_config", null)
            val jsonObj = if (existingRaw != null) JSONObject(existingRaw) else JSONObject()
            jsonObj.put("githubToken", newToken.trim())
            jsonObj.put("githubRepository", newRepo.trim())
            if (newBranch.isNotBlank()) jsonObj.put("githubBranch", newBranch.trim())
            jsonObj.put("isCustomerRepoPrivate", newPrivate)
            if (newCommitSha.isNotBlank()) jsonObj.put("lastPushedCommitSha", newCommitSha.trim())
            devPrefs.edit().putString("dev_config", jsonObj.toString()).apply()
        } catch (_: Exception) {}
    }

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
                        persistGitHubSettings(newToken = it)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = !isPrivateRepo,
                            onClick = {
                                isPrivateRepo = false
                                persistGitHubSettings(newPrivate = false)
                            },
                            label = { Text("Public Repo", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentColor, selectedLabelColor = TextPrimary)
                        )
                        FilterChip(
                            selected = isPrivateRepo,
                            onClick = {
                                isPrivateRepo = true
                                persistGitHubSettings(newPrivate = true)
                            },
                            label = { Text("Private Repo", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentColor, selectedLabelColor = TextPrimary)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isNewRepoMode) {
                        OutlinedTextField(
                            value = repositoryName,
                            onValueChange = { repositoryName = it },
                            label = { Text("Customer Repository Name") },
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
                            onValueChange = {
                                existingRepoOwner = it
                                cicdRepo = it.trim()
                                persistGitHubSettings(newRepo = it.trim())
                            },
                            label = { Text("Customer GitHub Owner/Repo (e.g. username/repo-name)") },
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
                        value = cicdBranch,
                        onValueChange = {
                            cicdBranch = it
                            persistGitHubSettings(newBranch = it)
                        },
                        label = { Text("Target Branch (auto-detects default branch if existing)") },
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
                                statusLog = "❌ GitHub Token Missing: Enter your GitHub Personal Access Token above."
                                return@Button
                            }
                            if (initialProject == null || initialProject.files.isEmpty()) {
                                Toast.makeText(context, "No project generated to push!", Toast.LENGTH_SHORT).show()
                                statusLog = "❌ No project files available to push."
                                return@Button
                            }
                            val targetInput = if (isNewRepoMode) repositoryName.trim() else existingRepoOwner.trim()
                            if (targetInput.isBlank()) {
                                Toast.makeText(context, "Please enter a repository name!", Toast.LENGTH_SHORT).show()
                                statusLog = "❌ Repository name cannot be empty."
                                return@Button
                            }

                            isProcessing = true
                            statusLog = "Authenticating with GitHub (GET /user), verifying customer repository, injecting .github/workflows/build-maya-android.yml, and pushing complete project tree..."

                            coroutineScope.launch(Dispatchers.IO) {
                                val pushRes = githubExecutor.pushCompleteProjectAtomically(
                                    token = githubToken.trim(),
                                    repoInput = targetInput,
                                    isNewRepo = isNewRepoMode,
                                    isPrivate = isPrivateRepo,
                                    requestedBranch = cicdBranch.trim().ifBlank { null },
                                    projectFiles = initialProject.files,
                                    commitMessage = commitMessage.ifBlank { "🚀 Feat: Built with MAYA AI Studio" },
                                    injectAndroidWorkflow = true,
                                    allowDeveloperRepo = false
                                )
                                withContext(Dispatchers.Main) {
                                    isProcessing = false
                                    if (pushRes.success && pushRes.data != null) {
                                        val data = pushRes.data
                                        authenticatedUserLogin = data.owner
                                        existingRepoOwner = data.fullName
                                        cicdRepo = data.fullName
                                        cicdBranch = data.branch
                                        isPrivateRepo = data.isPrivate
                                        latestPushedCommitSha = data.commitSha
                                        pushedRepoUrl = data.htmlUrl
                                        persistGitHubSettings(
                                            newToken = githubToken,
                                            newRepo = data.fullName,
                                            newBranch = data.branch,
                                            newPrivate = data.isPrivate,
                                            newCommitSha = data.commitSha
                                        )
                                        statusLog = buildString {
                                            appendLine("✅ Project pushed & workflow injected atomically!")
                                            appendLine("Owner: ${data.owner}")
                                            appendLine("Repository: ${data.repo} (${if (data.isPrivate) "Private" else "Public"})")
                                            appendLine("Full Name: ${data.fullName}")
                                            appendLine("Branch: ${data.branch}")
                                            appendLine("Commit SHA: ${data.commitSha}")
                                            appendLine("Files Pushed: ${data.filesPushed}")
                                            appendLine("Workflow Injected: .github/workflows/build-maya-android.yml (${data.workflowInjected})")
                                            append("GitHub Actions will now build both APK (assembleRelease) and AAB (bundleRelease). Switch to ⚡ CI/CD APK tab to monitor & download!")
                                        }
                                        Toast.makeText(context, "🎉 Pushed ${data.filesPushed} files (${data.commitSha.take(7)}) to ${data.fullName}!", Toast.LENGTH_LONG).show()
                                    } else {
                                        pushedRepoUrl = null
                                        statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                            "❌ Push Failed (${pushRes.errorCode}): ${pushRes.errorMessage}",
                                            githubToken
                                        )
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
                            Text("Pushing Complete Project & Workflow...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Push Project + CI/CD Workflow to GitHub", color = Color.White, fontWeight = FontWeight.Bold)
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
                    // CI/CD APK & DUAL BUILD SECTION (Tab 2)
                    Text("MAYA Dual Build System (GitHub Actions & Cloud Run)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    // Build Backend Selection: GitHub Actions | Cloud Run | Auto
                    Text("Build Backend:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "GITHUB_ACTIONS" to "GitHub Actions",
                            "CLOUD_RUN" to "Cloud Run",
                            "AUTO" to "Auto Route"
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = selectedBackendMode == key,
                                onClick = { selectedBackendMode = key },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accentColor,
                                    selectedLabelColor = TextPrimary,
                                    containerColor = DarkCard,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }

                    // Artifact Type Selection: APK | AAB | Web
                    Text("Artifact:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "APK" to "APK",
                            "AAB" to "AAB",
                            "WEB" to "Web"
                        ).forEach { (artKey, artLabel) ->
                            FilterChip(
                                selected = selectedArtifactType == artKey,
                                onClick = { selectedArtifactType = artKey },
                                label = { Text(artLabel, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accentColor,
                                    selectedLabelColor = TextPrimary,
                                    containerColor = DarkCard,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }

                    // Build Status Indicator: Queued | Running | Success | Failed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Build Status:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        val displayStatus = when (currentBuildStatusLabel.uppercase()) {
                            "QUEUED" -> "Queued"
                            "RUNNING", "IN_PROGRESS" -> "Running"
                            "SUCCESS" -> "Success"
                            "FAILED", "TIMEOUT", "UNCONFIGURED" -> "Failed"
                            else -> currentBuildStatusLabel
                        }
                        Text(
                            text = displayStatus,
                            color = when (displayStatus) {
                                "Success" -> Color(0xFF10B981)
                                "Failed" -> Color(0xFFEF4444)
                                "Running", "Queued" -> Color(0xFFF59E0B)
                                else -> TextMuted
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (latestPushedCommitSha.isNotBlank()) {
                        Text(
                            text = "Correlated Commit SHA: ${latestPushedCommitSha.take(12)}",
                            color = accentColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (selectedBackendMode == "CLOUD_RUN" || selectedBackendMode == "AUTO") {
                        OutlinedTextField(
                            value = cloudRunUrlInput,
                            onValueChange = { urlVal ->
                                cloudRunUrlInput = urlVal
                                try {
                                    val existingRaw = devPrefs.getString("dev_config", null)
                                    val jsonObj = if (existingRaw != null) JSONObject(existingRaw) else JSONObject()
                                    jsonObj.put("cloudRunBuildUrl", urlVal.trim())
                                    jsonObj.put("remoteBuildServerUrl", urlVal.trim())
                                    devPrefs.edit().putString("dev_config", jsonObj.toString()).apply()
                                } catch (_: Exception) {}
                            },
                            label = { Text("Cloud Run Build Server URL") },
                            placeholder = { Text("https://maya-remote-builder-xxx.run.app", color = TextMuted, fontSize = 12.sp) },
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
                    }

                    OutlinedTextField(
                        value = cicdRepo,
                        onValueChange = {
                            cicdRepo = it
                            existingRepoOwner = it
                            persistGitHubSettings(newRepo = it)
                        },
                        label = { Text("Customer Repository (owner/repo)") },
                        placeholder = { Text("username/my-android-app", color = TextMuted, fontSize = 12.sp) },
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
                        onValueChange = {
                            cicdBranch = it
                            persistGitHubSettings(newBranch = it)
                        },
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

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = pollUntilComplete,
                            onClick = { pollUntilComplete = !pollUntilComplete },
                            label = { Text("Monitor Until Complete", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accentColor,
                                selectedLabelColor = TextPrimary,
                                containerColor = DarkCard,
                                labelColor = TextSecondary
                            )
                        )
                        Text(
                            text = "Workflow: build-maya-android.yml",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Specific Run ID lookup
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = targetRunIdInput,
                            onValueChange = { targetRunIdInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Optional Run ID") },
                            placeholder = { Text("e.g. 37131422661", color = TextMuted, fontSize = 11.sp) },
                            modifier = Modifier.weight(1.3f),
                            singleLine = true,
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
                        OutlinedButton(
                            onClick = {
                                val runIdLong = targetRunIdInput.trim().toLongOrNull()
                                if (runIdLong == null || runIdLong <= 0L) {
                                    statusLog = "❌ Invalid Run ID: Enter a numeric GitHub Actions Run ID."
                                    return@OutlinedButton
                                }
                                if (cicdRepo.isBlank()) {
                                    statusLog = "❌ Repository Not Configured: Enter customer 'owner/repo' first."
                                    return@OutlinedButton
                                }
                                persistGitHubSettings()
                                isCheckingRuns = true
                                statusLog = "Inspecting GitHub Actions Run #$runIdLong on ${cicdRepo.trim()}..."
                                coroutineScope.launch(Dispatchers.IO) {
                                    val runRes = githubExecutor.fetchWorkflowRunById(cicdRepo.trim(), githubToken.trim(), runIdLong)
                                    val artRes = if (runRes.success) {
                                        githubExecutor.fetchRunArtifacts(cicdRepo.trim(), githubToken.trim(), runIdLong)
                                    } else null

                                    withContext(Dispatchers.Main) {
                                        isCheckingRuns = false
                                        if (!runRes.success || runRes.data == null) {
                                            currentBuildStatusLabel = "Failed"
                                            statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                "❌ Run #$runIdLong lookup failed (${runRes.errorCode}):\n${runRes.errorMessage}",
                                                githubToken
                                            )
                                        } else {
                                            val r = runRes.data
                                            val runInfo = WorkflowRunInfo(
                                                id = r.id,
                                                name = r.name,
                                                status = r.status,
                                                conclusion = r.conclusion,
                                                htmlUrl = r.htmlUrl,
                                                createdAt = r.createdAt,
                                                headSha = r.headSha
                                            )
                                            workflowRuns = listOf(runInfo) + workflowRuns.filterNot { it.id == r.id }
                                            val parsedArts = artRes?.data ?: emptyList()
                                            workflowArtifacts = parsedArts.map { a ->
                                                WorkflowArtifactInfo(
                                                    id = a.id,
                                                    name = a.name,
                                                    sizeInBytes = a.sizeInBytes,
                                                    downloadUrl = a.archiveDownloadUrl,
                                                    sha256 = a.sha256,
                                                    artifactType = a.artifactType
                                                )
                                            }
                                            val hasValidArtifact = workflowArtifacts.any { it.sizeInBytes > 0L }
                                            currentBuildStatusLabel = when {
                                                r.status.equals("queued", ignoreCase = true) || r.status.equals("waiting", ignoreCase = true) -> "Queued"
                                                r.status.equals("in_progress", ignoreCase = true) -> "Running"
                                                r.status.equals("completed", ignoreCase = true) && r.conclusion.equals("success", ignoreCase = true) && hasValidArtifact -> "Success"
                                                else -> "Failed"
                                            }
                                            statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                buildString {
                                                    appendLine("Backend: GITHUB_ACTIONS")
                                                    appendLine("Run #${r.id}: ${r.name} (commit: ${r.headSha.take(12)})")
                                                    appendLine("Status: ${r.status} | Conclusion: ${r.conclusion ?: "running..."}")
                                                    appendLine("Artifacts Found: ${workflowArtifacts.size}")
                                                    workflowArtifacts.forEach { a ->
                                                        appendLine("• [${a.artifactType}] ${a.name} (${a.sizeInBytes} bytes, SHA-256: ${a.sha256 ?: "verify-on-download"})")
                                                    }
                                                    if (r.status.equals("completed", true) && r.conclusion.equals("success", true) && !hasValidArtifact) {
                                                        appendLine("⚠️ Note: Run succeeded, but no non-expired artifact was returned by GitHub.")
                                                    }
                                                }.trim(),
                                                githubToken
                                            )
                                        }
                                    }
                                }
                            },
                            enabled = !isProcessing && !isCheckingRuns,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(0.9f)
                                .height(52.dp)
                        ) {
                            Text("Inspect Run", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val explicitBackend = when (selectedBackendMode) {
                                    "GITHUB_ACTIONS" -> com.cocateail.maya.studio.BuildBackend.GITHUB_ACTIONS
                                    "CLOUD_RUN" -> com.cocateail.maya.studio.BuildBackend.CLOUD_RUN
                                    else -> null
                                }

                                if (explicitBackend == com.cocateail.maya.studio.BuildBackend.GITHUB_ACTIONS && githubToken.isBlank()) {
                                    Toast.makeText(context, "Please enter your GitHub Token!", Toast.LENGTH_SHORT).show()
                                    currentBuildStatusLabel = "Failed"
                                    statusLog = "❌ GitHub Token Missing: Enter your GitHub Personal Access Token above."
                                    return@Button
                                }

                                val validRepo = com.cocateail.maya.studio.BuildSecurityValidator.sanitizeAndValidateGitHubRepo(cicdRepo)
                                if (explicitBackend == com.cocateail.maya.studio.BuildBackend.GITHUB_ACTIONS && validRepo == null) {
                                    currentBuildStatusLabel = "Failed"
                                    statusLog = "❌ Invalid Customer Repository: Enter valid 'owner/repo' (customer repository required)."
                                    return@Button
                                }
                                if (explicitBackend == com.cocateail.maya.studio.BuildBackend.GITHUB_ACTIONS &&
                                    !com.cocateail.maya.studio.BuildSecurityValidator.isValidGitHubBranch(cicdBranch)
                                ) {
                                    currentBuildStatusLabel = "Failed"
                                    statusLog = "❌ Invalid Branch: '${cicdBranch.trim()}' is not a valid git branch name."
                                    return@Button
                                }

                                persistGitHubSettings()
                                isProcessing = true
                                verifiedApkReport = null
                                verifiedAabReport = null
                                currentBuildStatusLabel = "Queued"
                                statusLog = "Triggering real build via ${explicitBackend?.name ?: "MAYA Backend Router"} for artifact [$selectedArtifactType]..."

                                coroutineScope.launch(Dispatchers.IO) {
                                    val projType = if (selectedArtifactType == "WEB") {
                                        com.cocateail.maya.studio.BuilderProjectType.WEB_APP
                                    } else {
                                        com.cocateail.maya.studio.BuilderProjectType.ANDROID_APP
                                    }
                                    val orchestrator = com.cocateail.maya.studio.DualBuildOrchestrator(context, projType)
                                    val filesMap = mutableMapOf<String, String>()
                                    initialProject?.files?.forEach { pf ->
                                        if (!pf.isFolder && com.cocateail.maya.studio.BuildSecurityValidator.isSafeRelativePath(pf.path)) {
                                            filesMap[pf.path] = if (pf.isBinary && !pf.base64Content.isNullOrBlank()) {
                                                "__BASE64_BINARY__:${pf.base64Content}"
                                            } else {
                                                pf.content
                                            }
                                        }
                                    }
                                    val buildCmd = when (selectedArtifactType) {
                                        "AAB" -> "./gradlew bundleRelease"
                                        "WEB" -> "npm run build"
                                        else -> "./gradlew assembleRelease"
                                    }
                                    val shouldPushBeforeBuild = latestPushedCommitSha.isBlank() && filesMap.isNotEmpty()
                                    val req = com.cocateail.maya.studio.BuildRequest(
                                        projectType = if (selectedArtifactType == "WEB") "WEB" else "ANDROID",
                                        files = filesMap,
                                        buildCommand = buildCmd,
                                        requestedArtifactType = selectedArtifactType,
                                        backend = explicitBackend,
                                        metadata = buildMap {
                                            put("githubRepo", cicdRepo.trim())
                                            put("githubBranch", cicdBranch.trim())
                                            if (latestPushedCommitSha.isNotBlank()) {
                                                put("commitSha", latestPushedCommitSha.trim())
                                            }
                                            if (shouldPushBeforeBuild) {
                                                put("pushFiles", "true")
                                            }
                                        }
                                    )
                                    val result = orchestrator.executeUnifiedBuild(
                                        request = req,
                                        pollGitHubCompletion = pollUntilComplete,
                                        maxPollAttempts = 20,
                                        pollIntervalMs = 6000L,
                                        onProgress = { runUpdate, pollStep, progressMsg ->
                                            coroutineScope.launch(Dispatchers.Main) {
                                                if (runUpdate != null) {
                                                    if (runUpdate.headSha.isNotBlank()) {
                                                        latestPushedCommitSha = runUpdate.headSha
                                                    }
                                                    currentBuildStatusLabel = when (runUpdate.normalizedStatus()) {
                                                        "QUEUED" -> "Queued"
                                                        "RUNNING" -> "Running"
                                                        "SUCCESS" -> "Running" // Wait for artifact confirmation before Success
                                                        else -> "Failed"
                                                    }
                                                }
                                                statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                    "⏳ [Poll #$pollStep] $progressMsg",
                                                    githubToken
                                                )
                                            }
                                        }
                                    )
                                    com.cocateail.maya.studio.BuilderAgent.lastBuildResult = result

                                    val runsRes = if (result.backend == com.cocateail.maya.studio.BuildBackend.GITHUB_ACTIONS && githubToken.isNotBlank() && cicdRepo.isNotBlank()) {
                                        githubExecutor.fetchWorkflowRuns(cicdRepo.trim(), githubToken.trim(), cicdBranch.trim())
                                    } else null

                                    val fetchedRuns = runsRes?.data?.map { r ->
                                        WorkflowRunInfo(
                                            id = r.id,
                                            name = r.name,
                                            status = r.status,
                                            conclusion = r.conclusion,
                                            htmlUrl = r.htmlUrl,
                                            createdAt = r.createdAt,
                                            headSha = r.headSha
                                        )
                                    } ?: workflowRuns

                                    val runIdForArtifacts = result.buildId.toLongOrNull() ?: fetchedRuns.firstOrNull()?.id
                                    val fetchedArtifacts = if (result.backend == com.cocateail.maya.studio.BuildBackend.GITHUB_ACTIONS &&
                                        runIdForArtifacts != null && githubToken.isNotBlank() && cicdRepo.isNotBlank()
                                    ) {
                                        githubExecutor.fetchRunArtifacts(cicdRepo.trim(), githubToken.trim(), runIdForArtifacts).data?.map { a ->
                                            WorkflowArtifactInfo(
                                                id = a.id,
                                                name = a.name,
                                                sizeInBytes = a.sizeInBytes,
                                                downloadUrl = a.archiveDownloadUrl,
                                                sha256 = a.sha256,
                                                artifactType = a.artifactType
                                            )
                                        } ?: emptyList()
                                    } else workflowArtifacts

                                    withContext(Dispatchers.Main) {
                                        isProcessing = false
                                        latestBuildResult = result
                                        workflowRuns = fetchedRuns
                                        workflowArtifacts = fetchedArtifacts
                                        currentBuildStatusLabel = when (result.status.uppercase()) {
                                            "QUEUED" -> "Queued"
                                            "RUNNING", "IN_PROGRESS" -> "Running"
                                            "SUCCESS" -> if (result.success) "Success" else "Failed"
                                            else -> "Failed"
                                        }
                                        statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                            buildString {
                                                appendLine("Backend: ${result.backend.name}")
                                                appendLine("Status: ${result.status} (exitCode=${result.exitCode})")
                                                appendLine("Artifact Type: ${result.artifactType ?: selectedArtifactType}")
                                                if (!result.artifactName.isNullOrBlank()) {
                                                    appendLine("Artifact: ${result.artifactName} (${result.artifactSize} bytes)")
                                                }
                                                if (!result.sha256.isNullOrBlank()) {
                                                    appendLine("SHA-256: ${result.sha256}")
                                                }
                                                if (!result.errorCode.isNullOrBlank()) {
                                                    appendLine("Error Code: ${result.errorCode}")
                                                }
                                                append(result.message.ifBlank { result.stderr.ifBlank { result.stdout } })
                                            }.trim(),
                                            githubToken
                                        )
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
                                if (cicdRepo.isBlank()) {
                                    currentBuildStatusLabel = "Failed"
                                    statusLog = "❌ Customer Repository Not Configured: Enter 'owner/repo' above or push your project first."
                                    return@OutlinedButton
                                }
                                persistGitHubSettings()
                                isCheckingRuns = true
                                statusLog = "Checking workflow runs on ${cicdRepo.trim()} (${cicdBranch.trim()})..."
                                coroutineScope.launch(Dispatchers.IO) {
                                    val runsRes = githubExecutor.fetchWorkflowRuns(
                                        ownerRepo = cicdRepo.trim(),
                                        token = githubToken.trim(),
                                        branch = cicdBranch.trim()
                                    )
                                    val allParsedRuns = runsRes.data ?: emptyList()
                                    // Correlate with latestPushedCommitSha if available so stale runs are never mistaken for latest build
                                    val correlatedParsedRuns = if (latestPushedCommitSha.isNotBlank()) {
                                        allParsedRuns.filter {
                                            com.cocateail.maya.studio.BuildSecurityValidator.matchesExpectedCommitSha(it.headSha, latestPushedCommitSha)
                                        }
                                    } else {
                                        allParsedRuns
                                    }
                                    val runs = correlatedParsedRuns.map { r ->
                                        WorkflowRunInfo(
                                            id = r.id,
                                            name = r.name,
                                            status = r.status,
                                            conclusion = r.conclusion,
                                            htmlUrl = r.htmlUrl,
                                            createdAt = r.createdAt,
                                            headSha = r.headSha
                                        )
                                    }

                                    val artifacts = if (runsRes.success && runs.isNotEmpty()) {
                                        githubExecutor.fetchRunArtifacts(
                                            ownerRepo = cicdRepo.trim(),
                                            token = githubToken.trim(),
                                            runId = runs.first().id
                                        ).data?.map { a ->
                                            WorkflowArtifactInfo(
                                                id = a.id,
                                                name = a.name,
                                                sizeInBytes = a.sizeInBytes,
                                                downloadUrl = a.archiveDownloadUrl,
                                                sha256 = a.sha256,
                                                artifactType = a.artifactType
                                            )
                                        } ?: emptyList()
                                    } else emptyList()

                                    withContext(Dispatchers.Main) {
                                        isCheckingRuns = false
                                        if (!runsRes.success) {
                                            currentBuildStatusLabel = "Failed"
                                            statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                "❌ GitHub Status Check Failed (${runsRes.errorCode}):\n${runsRes.errorMessage}",
                                                githubToken
                                            )
                                        } else if (runs.isNotEmpty()) {
                                            workflowRuns = runs
                                            workflowArtifacts = artifacts
                                            val latest = runs.first()
                                            val hasValidArtifact = artifacts.any { it.sizeInBytes > 0L }
                                            currentBuildStatusLabel = when {
                                                latest.status.equals("queued", ignoreCase = true) || latest.status.equals("waiting", ignoreCase = true) -> "Queued"
                                                latest.status.equals("in_progress", ignoreCase = true) -> "Running"
                                                latest.status.equals("completed", ignoreCase = true) &&
                                                    latest.conclusion.equals("success", ignoreCase = true) &&
                                                    hasValidArtifact -> "Success"
                                                else -> "Failed"
                                            }
                                            statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                buildString {
                                                    appendLine("Backend: GITHUB_ACTIONS")
                                                    appendLine("Workflow: ${latest.name} (Run #${latest.id})")
                                                    appendLine("Commit SHA: ${latest.headSha.ifBlank { "n/a" }}")
                                                    appendLine("Status: ${latest.status} | Conclusion: ${latest.conclusion ?: "running..."}")
                                                    appendLine("Runs found: ${runs.size} | Artifacts for #${latest.id}: ${artifacts.size}")
                                                    if (latest.status.equals("completed", true) && latest.conclusion.equals("success", true) && !hasValidArtifact) {
                                                        appendLine("⚠️ Run #${latest.id} completed, but no valid non-empty artifact was found.")
                                                    }
                                                }.trim(),
                                                githubToken
                                            )
                                        } else {
                                            workflowRuns = emptyList()
                                            workflowArtifacts = emptyList()
                                            statusLog = if (latestPushedCommitSha.isNotBlank() && allParsedRuns.isNotEmpty()) {
                                                "⏳ No workflow run found yet matching latest pushed commit SHA ${latestPushedCommitSha.take(12)} (${allParsedRuns.size} older run(s) ignored)."
                                            } else {
                                                "No workflow runs found for ${cicdRepo.trim()} (${cicdBranch.trim()})."
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isCheckingRuns && !isProcessing,
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
                        Text("Recent Actions Runs (tap to load artifacts):", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))

                        workflowRuns.take(4).forEach { run ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        targetRunIdInput = run.id.toString()
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val artRes = githubExecutor.fetchRunArtifacts(cicdRepo.trim(), githubToken.trim(), run.id)
                                            withContext(Dispatchers.Main) {
                                                if (artRes.success) {
                                                    workflowArtifacts = (artRes.data ?: emptyList()).map { a ->
                                                        WorkflowArtifactInfo(
                                                            id = a.id,
                                                            name = a.name,
                                                            sizeInBytes = a.sizeInBytes,
                                                            downloadUrl = a.archiveDownloadUrl,
                                                            sha256 = a.sha256,
                                                            artifactType = a.artifactType
                                                        )
                                                    }
                                                    val hasArt = workflowArtifacts.any { it.sizeInBytes > 0L }
                                                    currentBuildStatusLabel = when {
                                                        run.status.equals("queued", true) -> "Queued"
                                                        run.status.equals("in_progress", true) -> "Running"
                                                        run.status.equals("completed", true) && run.conclusion.equals("success", true) && hasArt -> "Success"
                                                        else -> "Failed"
                                                    }
                                                    statusLog = "Loaded ${workflowArtifacts.size} artifact(s) for Run #${run.id} (commit ${run.headSha.take(8)})."
                                                } else {
                                                    statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                        "❌ Could not load artifacts for Run #${run.id}: ${artRes.errorMessage}",
                                                        githubToken
                                                    )
                                                }
                                            }
                                        }
                                    },
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
                                            text = "#${run.id} ${if (run.headSha.isNotBlank()) "(${run.headSha.take(7)})" else ""}",
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
                        Text("Generated Artifacts (tap to download & verify APK / AAB):", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)

                        val hasApkInList = workflowArtifacts.any { it.artifactType.equals("APK", true) || it.name.contains("apk", true) }
                        val hasAabInList = workflowArtifacts.any { it.artifactType.equals("AAB", true) || it.name.contains("aab", true) }
                        if (hasApkInList && hasAabInList) {
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    if (githubToken.isBlank()) {
                                        Toast.makeText(context, "Enter your GitHub Token to download artifacts!", Toast.LENGTH_SHORT).show()
                                        return@OutlinedButton
                                    }
                                    val runIdForBoth = targetRunIdInput.toLongOrNull() ?: workflowRuns.firstOrNull()?.id ?: 0L
                                    if (runIdForBoth <= 0L) {
                                        statusLog = "❌ Select or inspect a Workflow Run first."
                                        return@OutlinedButton
                                    }
                                    isDownloadingArtifactId = -1L
                                    statusLog = "⏳ Downloading & verifying BOTH APK and AAB artifacts for Run #$runIdForBoth..."
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val dualRes = githubExecutor.downloadAndVerifyBothArtifacts(
                                            ownerRepo = cicdRepo.trim(),
                                            token = githubToken.trim(),
                                            runId = runIdForBoth
                                        )
                                        withContext(Dispatchers.Main) {
                                            isDownloadingArtifactId = null
                                            if (dualRes.success && dualRes.data != null) {
                                                val both = dualRes.data
                                                verifiedApkReport = both.apkReport
                                                verifiedAabReport = both.aabReport
                                                currentBuildStatusLabel = "Success"
                                                statusLog = buildString {
                                                    appendLine("✅ Both APK & AAB Artifacts Downloaded & Cryptographically Verified!")
                                                    both.apkReport?.let { apk ->
                                                        appendLine("• APK: ${apk.fileName} (${apk.sizeBytes} B, signed=${apk.isSigned}, SHA-256=${apk.sha256.take(16)}...)")
                                                    }
                                                    both.aabReport?.let { aab ->
                                                        appendLine("• AAB: ${aab.fileName} (${aab.sizeBytes} B, base=${aab.hasBaseModule}, BundleConfig=${aab.hasBundleConfig}, SHA-256=${aab.sha256.take(16)}...)")
                                                    }
                                                }.trim()
                                            } else {
                                                currentBuildStatusLabel = "Failed"
                                                statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                    "❌ Dual APK+AAB Verification Failed (${dualRes.errorCode}):\n${dualRes.errorMessage}",
                                                    githubToken
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = isDownloadingArtifactId == null,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = accentColor)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download & Verify Both (APK + AAB)", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }

                        workflowArtifacts.forEach { art ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(enabled = isDownloadingArtifactId == null) {
                                        if (githubToken.isBlank()) {
                                            Toast.makeText(context, "Enter your GitHub Token to download artifacts!", Toast.LENGTH_SHORT).show()
                                            statusLog = "❌ GitHub Token required: GitHub Actions artifact downloads require an authenticated PAT."
                                            return@clickable
                                        }
                                        persistGitHubSettings()
                                        isDownloadingArtifactId = art.id
                                        val isAab = art.artifactType.equals("AAB", ignoreCase = true) || art.name.lowercase().contains("aab")
                                        statusLog = "⏳ Downloading & verifying ${if (isAab) "AAB" else "APK"} '${art.name}' (${art.sizeInBytes} bytes) from GitHub Actions..."
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val parsedArt = com.cocateail.maya.studio.GitHubParsedArtifact(
                                                id = art.id,
                                                name = art.name,
                                                sizeInBytes = art.sizeInBytes,
                                                archiveDownloadUrl = art.downloadUrl,
                                                sha256 = art.sha256,
                                                artifactType = if (isAab) "AAB" else art.artifactType
                                            )
                                            if (isAab) {
                                                val aabRes = githubExecutor.downloadAndVerifyAabArtifact(
                                                    ownerRepo = cicdRepo.trim(),
                                                    token = githubToken.trim(),
                                                    artifact = parsedArt
                                                )
                                                withContext(Dispatchers.Main) {
                                                    isDownloadingArtifactId = null
                                                    if (aabRes.success && aabRes.data != null) {
                                                        val rep = aabRes.data
                                                        verifiedAabReport = rep
                                                        currentBuildStatusLabel = "Success"
                                                        statusLog = buildString {
                                                            appendLine("✅ AAB Bundle Downloaded & Verified!")
                                                            appendLine("File: ${rep.fileName}")
                                                            appendLine("Size: ${rep.sizeBytes} bytes (${String.format("%.2f", rep.sizeBytes / (1024.0 * 1024.0))} MB)")
                                                            appendLine("AAB SHA-256: ${rep.sha256}")
                                                            if (!rep.archiveSha256.isNullOrBlank()) {
                                                                appendLine("Archive ZIP SHA-256: ${rep.archiveSha256}")
                                                            }
                                                            appendLine("ZIP Archive Integrity: ${if (rep.isZipValid) "VALID" else "INVALID"}")
                                                            appendLine("base/ Module: ${if (rep.hasBaseModule) "PRESENT" else "MISSING"}")
                                                            appendLine("base/manifest/AndroidManifest.xml: ${if (rep.hasManifest) "PRESENT" else "MISSING"}")
                                                            appendLine("base/dex/*.dex: ${if (rep.hasDex) "PRESENT" else "MISSING"}")
                                                            appendLine("Resources (base/resources.pb | base/res/): ${if (rep.hasResources) "PRESENT" else "MISSING"}")
                                                            appendLine("BundleConfig.pb: ${if (rep.hasBundleConfig) "PRESENT" else "MISSING"}")
                                                            append("Saved Path: ${rep.filePath}")
                                                        }
                                                    } else {
                                                        currentBuildStatusLabel = "Failed"
                                                        statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                            "❌ AAB Download/Verification Failed (${aabRes.errorCode}):\n${aabRes.errorMessage}",
                                                            githubToken
                                                        )
                                                    }
                                                }
                                            } else {
                                                val dlRes = githubExecutor.downloadAndVerifyArtifact(
                                                    ownerRepo = cicdRepo.trim(),
                                                    token = githubToken.trim(),
                                                    artifact = parsedArt
                                                )
                                                withContext(Dispatchers.Main) {
                                                    isDownloadingArtifactId = null
                                                    if (dlRes.success && dlRes.data != null) {
                                                        val rep = dlRes.data
                                                        verifiedApkReport = rep
                                                        currentBuildStatusLabel = "Success"
                                                        statusLog = buildString {
                                                            appendLine("✅ APK Artifact Downloaded & Verified!")
                                                            appendLine("File: ${rep.fileName}")
                                                            appendLine("Size: ${rep.sizeBytes} bytes (${String.format("%.2f", rep.sizeBytes / (1024.0 * 1024.0))} MB)")
                                                            appendLine("APK SHA-256: ${rep.sha256}")
                                                            if (!rep.archiveSha256.isNullOrBlank()) {
                                                                appendLine("Archive ZIP SHA-256: ${rep.archiveSha256}")
                                                            }
                                                            appendLine("ZIP Magic (PK\\x03\\x04): ${if (rep.hasZipMagic) "VALID" else "INVALID"}")
                                                            appendLine("AndroidManifest.xml: ${if (rep.hasAndroidManifest) "PRESENT" else "MISSING"}")
                                                            appendLine("DEX / Resources: ${if (rep.hasDexOrResources) "PRESENT" else "MISSING"}")
                                                            appendLine("APK Signature (V1/V2/V3): ${if (rep.isSigned) "SIGNED" else "UNSIGNED"}")
                                                            if (!rep.packageName.isNullOrBlank()) {
                                                                appendLine("Package: ${rep.packageName} (v${rep.versionName ?: "1.0"})")
                                                            }
                                                            append("Saved Path: ${rep.filePath}")
                                                        }
                                                    } else {
                                                        currentBuildStatusLabel = "Failed"
                                                        statusLog = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(
                                                            "❌ APK Download/Verification Failed (${dlRes.errorCode}):\n${dlRes.errorMessage}",
                                                            githubToken
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF064E3B).copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, Color(0xFF10B981))
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(art.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        val sizeMb = String.format("%.2f", art.sizeInBytes / (1024.0 * 1024.0))
                                        Text(
                                            text = "$sizeMb MB (${art.sizeInBytes} bytes) • ${art.artifactType}",
                                            color = TextSecondary,
                                            fontSize = 10.sp
                                        )
                                        if (!art.sha256.isNullOrBlank()) {
                                            Text(
                                                text = "SHA-256: ${art.sha256.take(20)}...",
                                                color = TextMuted,
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                    if (isDownloadingArtifactId == art.id) {
                                        CircularProgressIndicator(
                                            color = Color(0xFF10B981),
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = "Download & Verify Artifact", tint = Color(0xFF10B981))
                                    }
                                }
                            }
                        }
                    }

                    verifiedApkReport?.let { report ->
                        if (report.valid && report.fileName.endsWith(".apk", ignoreCase = true)) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val (launched, msg) = githubExecutor.installDownloadedApk(java.io.File(report.filePath))
                                        statusLog = if (launched) "📦 $msg" else "⚠️ $msg"
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Install APK",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        val (saved, msg) = githubExecutor.exportVerifiedArtifactToDownloads(
                                            artifactFile = java.io.File(report.filePath),
                                            artifactType = "APK"
                                        )
                                        statusLog = if (saved) "💾 $msg" else "❌ $msg"
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF10B981))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Save APK",
                                        color = Color(0xFF10B981),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    verifiedAabReport?.let { aabReport ->
                        if (aabReport.isValid && aabReport.fileName.endsWith(".aab", ignoreCase = true)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val (saved, msg) = githubExecutor.exportVerifiedArtifactToDownloads(
                                        artifactFile = java.io.File(aabReport.filePath),
                                        artifactType = "AAB"
                                    )
                                    statusLog = if (saved) "💾 $msg" else "❌ $msg"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                Icon(Icons.Default.FolderZip, contentDescription = null, tint = accentColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Save Verified AAB to Downloads (${aabReport.fileName})",
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
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
                            text = com.cocateail.maya.studio.BuildSecurityValidator.redactSecrets(statusLog, githubToken),
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

// REST API HELPER FOR GITHUB IMPORT
private suspend fun importProjectFromGitHubApi(
    token: String,
    repoPath: String
): GeneratedProject = withContext(Dispatchers.IO) {
    val cleanOwnerRepo = com.cocateail.maya.studio.BuildSecurityValidator.sanitizeAndValidateGitHubRepo(repoPath)
        ?: throw IllegalArgumentException("Invalid GitHub repository format. Expected 'owner/repo'.")

    val apiUrl = URL("https://api.github.com/repos/$cleanOwnerRepo/contents")
    val conn = apiUrl.openConnection() as HttpURLConnection
    conn.connectTimeout = 30000
    conn.readTimeout = 30000
    conn.requestMethod = "GET"
    if (token.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $token")
    }
    conn.setRequestProperty("Accept", "application/vnd.github+json")

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

        if (com.cocateail.maya.studio.BuildSecurityValidator.isSafeRelativePath(path) &&
            downloadUrl.isNotBlank() && downloadUrl != "null"
        ) {
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
    val createdAt: String,
    val headSha: String = ""
)

data class WorkflowArtifactInfo(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val downloadUrl: String,
    val sha256: String? = null,
    val artifactType: String = "APK"
)
