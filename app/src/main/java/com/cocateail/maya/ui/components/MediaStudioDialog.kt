package com.cocateail.maya.ui.components

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.cocateail.maya.util.MediaDownloadHelper
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.cocateail.maya.ui.theme.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class MediaType {
    IMAGE,
    VIDEO
}

data class WanVideoModel(
    val id: String,
    val displayName: String,
    val description: String,
    val apiParam: String
)

val wanVideoModels = listOf(
    WanVideoModel(
        id = "wan_2_2_i2v_14b_lightning",
        displayName = "Wan 2.2 I2V 14B Lightning",
        description = "Image to Video (⚡ Ultra Fast 14B)",
        apiParam = "wan-i2v-lightning"
    ),
    WanVideoModel(
        id = "wan_2_2_t2v_14b",
        displayName = "Wan 2.2 T2V 14B",
        description = "Text to Video (🎬 14B High Quality)",
        apiParam = "wan-t2v-14b"
    ),
    WanVideoModel(
        id = "wan_2_2_ti2v_5b",
        displayName = "Wan 2.2 TI2V 5B",
        description = "Text + Image to Video (🎨 5B Hybrid)",
        apiParam = "wan-ti2v-5b"
    ),
    WanVideoModel(
        id = "wan_2_2_animate_14b",
        displayName = "Wan 2.2 Animate 14B",
        description = "Character Animation Model (🕺 Motion)",
        apiParam = "wan-animate"
    ),
    WanVideoModel(
        id = "pollinations_turbo_speed",
        displayName = "Fast Speed Engine",
        description = "Instant Fallback / Speed Mode (🚀 Fast)",
        apiParam = "turbo"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaStudioDialog(
    accentColor: Color,
    onDismiss: () -> Unit,
    onGenerateMedia: (mediaUrl: String, prompt: String, isVideo: Boolean) -> Unit
) {
    var selectedType by remember { mutableStateOf(MediaType.IMAGE) }
    var promptText by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf("Photorealistic") }
    var selectedAspectRatio by remember { mutableStateOf("1:1") }
    var selectedVideoModel by remember { mutableStateOf(wanVideoModels[0]) }

    var isGenerating by remember { mutableStateOf(false) }
    var generatedPreviewUrl by remember { mutableStateOf<String?>(null) }

    val styleOptions = listOf("Photorealistic", "Cyberpunk", "Anime", "Cinematic", "3D Render", "Fantasy")
    val aspectRatios = listOf("1:1", "16:9", "9:16")
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "MAYA AI MEDIA STUDIO",
                                color = accentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "Image & Video Generator",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_media_studio_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Media Type Selector Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkCard)
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selectedType == MediaType.IMAGE) accentColor else Color.Transparent)
                            .clickable { selectedType = MediaType.IMAGE }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = if (selectedType == MediaType.IMAGE) TextPrimary else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "AI Image",
                                color = if (selectedType == MediaType.IMAGE) TextPrimary else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selectedType == MediaType.VIDEO) accentColor else Color.Transparent)
                            .clickable { selectedType = MediaType.VIDEO }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = if (selectedType == MediaType.VIDEO) TextPrimary else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "AI Video",
                                color = if (selectedType == MediaType.VIDEO) TextPrimary else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Prompt Input Field
                Text("Describe your creation", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = {
                        Text(
                            if (selectedType == MediaType.IMAGE) "e.g., A majestic white tiger in a glowing neon futuristic forest"
                            else "e.g., Cinematic slow motion drone shot of ocean waves at sunset",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .testTag("media_prompt_input_field"),
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

                // Video Model Selector (if Video mode selected)
                if (selectedType == MediaType.VIDEO) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("MAYA Video Engine Model 🎬", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        wanVideoModels.forEach { model ->
                            val isSelected = selectedVideoModel.id == model.id
                            Surface(
                                onClick = { selectedVideoModel = model },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) accentColor.copy(alpha = 0.2f) else DarkCard,
                                border = BorderStroke(1.dp, if (isSelected) accentColor else DarkBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = model.displayName,
                                            color = if (isSelected) accentColor else TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = model.description,
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }

                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedVideoModel = model },
                                        colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Style Selector Chips
                Text("Artistic Style", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    styleOptions.take(3).forEach { style ->
                        FilterChip(
                            selected = selectedStyle == style,
                            onClick = { selectedStyle = style },
                            label = { Text(style, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accentColor,
                                selectedLabelColor = TextPrimary,
                                containerColor = DarkCard,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Aspect Ratio Selector
                Text("Aspect Ratio", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    aspectRatios.forEach { ratio ->
                        FilterChip(
                            selected = selectedAspectRatio == ratio,
                            onClick = { selectedAspectRatio = ratio },
                            label = { Text(ratio, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accentColor,
                                selectedLabelColor = TextPrimary,
                                containerColor = DarkCard,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Preview Box if generated
                if (generatedPreviewUrl != null) {
                    Text("Generated Result", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(DarkCard)
                            .border(1.dp, accentColor, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = generatedPreviewUrl,
                            contentDescription = "AI Generated Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            generatedPreviewUrl?.let { url ->
                                MediaDownloadHelper.downloadToGallery(
                                    context = context,
                                    mediaUrl = url,
                                    isVideo = selectedType == MediaType.VIDEO,
                                    customTitle = promptText
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, accentColor)
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = "Save", tint = accentColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedType == MediaType.VIDEO) "Download Video to Gallery" else "Download Pic to Gallery",
                            color = accentColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Action Buttons
                Button(
                    onClick = {
                        val inputPrompt = promptText.ifBlank {
                            if (selectedType == MediaType.IMAGE) "A beautiful futuristic cyberpunk landscape, 8k resolution"
                            else "Cinematic motion of sunset over ocean waves"
                        }
                        isGenerating = true

                        val fullPrompt = "$inputPrompt, $selectedStyle style, 8k resolution, detailed"
                        val encodedPrompt = URLEncoder.encode(fullPrompt, StandardCharsets.UTF_8.toString())
                        val randomSeed = (10000..99999).random()

                        val width = if (selectedAspectRatio == "16:9") 1280 else if (selectedAspectRatio == "9:16") 720 else 1024
                        val height = if (selectedAspectRatio == "16:9") 720 else if (selectedAspectRatio == "9:16") 1280 else 1024

                        val mediaUrl = if (selectedType == MediaType.IMAGE) {
                            "https://image.pollinations.ai/prompt/$encodedPrompt?width=$width&height=$height&seed=$randomSeed&nologo=true&model=flux"
                        } else {
                            "https://image.pollinations.ai/prompt/$encodedPrompt?width=$width&height=$height&seed=$randomSeed&nologo=true&model=${selectedVideoModel.apiParam}"
                        }

                        generatedPreviewUrl = mediaUrl
                        isGenerating = false

                        val finalPromptDesc = if (selectedType == MediaType.VIDEO) {
                            "$inputPrompt [Model: ${selectedVideoModel.displayName}]"
                        } else {
                            inputPrompt
                        }

                        onGenerateMedia(mediaUrl, finalPromptDesc, selectedType == MediaType.VIDEO)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("generate_media_submit_button")
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Generating AI Media...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedType == MediaType.IMAGE) "Generate AI Image ✨" else "Generate AI Video ✨",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
