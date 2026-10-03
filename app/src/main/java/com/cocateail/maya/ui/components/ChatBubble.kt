package com.cocateail.maya.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import com.cocateail.maya.util.MediaDownloadHelper
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.cocateail.maya.data.db.MessageEntity
import com.cocateail.maya.data.specialist.SpecialistType
import com.cocateail.maya.data.specialist.TaskClassifier
import com.cocateail.maya.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

sealed class MessageContentChunk {
    data class TextChunk(val text: String) : MessageContentChunk()
    data class CodeChunk(val language: String, val code: String) : MessageContentChunk()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: MessageEntity,
    characterName: String,
    accentColor: Color = VermilionPrimary,
    onPinClick: () -> Unit,
    onSpeakClick: () -> Unit,
    onReplyClick: () -> Unit = {},
    onQuoteClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val formattedTime = timeFormat.format(Date(message.createdAt))
    var isPlayingVideo by remember { mutableStateOf(false) }

    // Parse message into text and code blocks
    val contentChunks = remember(message.content) {
        parseMessageContent(message.content)
    }

    // Detect specialist type if message contains specialized dev content
    val detectedSpecialist = remember(message.content, isUser) {
        if (!isUser) TaskClassifier.classify(message.content) else SpecialistType.GENERAL
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 12.dp)
            .testTag("chat_bubble_${message.id}"),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Speaker header for assistant
        if (!isUser) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = characterName,
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                if (detectedSpecialist != SpecialistType.GENERAL) {
                    SpecialistBadge(specialistType = detectedSpecialist)
                }
            }
        }

        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 20.dp
                    )
                )
                .background(if (isUser) UserBubbleColor else AssistantBubbleColor)
                .border(
                    width = 1.dp,
                    color = if (isUser) accentColor.copy(alpha = 0.4f) else DarkBorder,
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 20.dp
                    )
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        onReplyClick()
                        Toast.makeText(context, "Replying...", Toast.LENGTH_SHORT).show()
                    }
                )
                .padding(14.dp)
        ) {
            Column {
                if (message.replyToId != null && message.repliedMessageText != null) {
                    val quotedSender = message.repliedMessageSender ?: "Unknown"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurface.copy(alpha = 0.5f))
                            .clickable {
                                onQuoteClick?.invoke(message.replyToId)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(accentColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = quotedSender,
                                color = accentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = message.repliedMessageText,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (!message.imageUrl.isNullOrEmpty()) {
                    val isVideo = message.content.contains("AI Generated Video") || message.imageUrl.contains("video=true")
                    val context = LocalContext.current
                    val imageRequest = remember(message.imageUrl) {
                        ImageRequest.Builder(context)
                            .data(message.imageUrl)
                            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                            .crossfade(true)
                            .build()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkCard)
                            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clickable {
                                if (isVideo) {
                                    isPlayingVideo = true
                                }
                            }
                            .padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SubcomposeAsyncImage(
                            model = imageRequest,
                            contentDescription = if (isVideo) "AI Generated Video" else "AI Generated Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            loading = {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = accentColor,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isVideo) "Generating AI Video..." else "Rendering AI Art...",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            },
                            error = {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data("https://picsum.photos/800/600?random=" + message.id.hashCode())
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "AI Media",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        )

                        if (isVideo) {
                            Surface(
                                color = DarkBackground.copy(alpha = 0.7f),
                                shape = CircleShape,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play Video",
                                    tint = accentColor,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }

                    // Sleek Download to Gallery Action Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        FilledTonalButton(
                            onClick = {
                                MediaDownloadHelper.downloadToGallery(
                                    context = context,
                                    mediaUrl = message.imageUrl,
                                    isVideo = isVideo,
                                    customTitle = message.content
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = DarkBackground.copy(alpha = 0.8f),
                                contentColor = accentColor
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download to Gallery",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isVideo) "Save Video to Gallery" else "Save Pic to Gallery",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isPlayingVideo && !message.imageUrl.isNullOrEmpty()) {
                        VideoPlayerDialog(
                            videoUrl = message.imageUrl,
                            promptTitle = message.content,
                            accentColor = accentColor,
                            onDismiss = { isPlayingVideo = false }
                        )
                    }
                }

                if (message.content.startsWith("[BUILDER_STATUS]")) {
                    val lines = message.content.split("\n")
                    val status = lines.firstOrNull { it.startsWith("status:") }?.removePrefix("status:")?.trim() ?: "BUILDING"
                    val text = lines.firstOrNull { it.startsWith("message:") }?.removePrefix("message:")?.trim() ?: "Processing..."
                    val iter = lines.firstOrNull { it.startsWith("iteration:") }?.removePrefix("iteration:")?.trim()?.toIntOrNull() ?: 1
                    
                    BuilderChatStatus(
                        status = status,
                        statusText = text,
                        iteration = iter,
                        accentColor = accentColor
                    )
                } else {
                    contentChunks.forEach { chunk ->
                        when (chunk) {
                            is MessageContentChunk.TextChunk -> {
                                if (chunk.text.isNotBlank()) {
                                    Text(
                                        text = chunk.text.trim(),
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                            is MessageContentChunk.CodeChunk -> {
                                CodeBlockView(
                                    language = chunk.language,
                                    code = chunk.code,
                                    accentColor = accentColor
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedTime,
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!isUser) {
                            IconButton(
                                onClick = onSpeakClick,
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("speak_button_${message.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Speak response",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Message", message.content)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("copy_button_${message.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy message",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                                )
                        }

                        IconButton(
                            onClick = onPinClick,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("pin_button_${message.id}")
                        ) {
                            Icon(
                                imageVector = if (message.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                contentDescription = "Pin message",
                                tint = if (message.pinned) accentColor else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onReplyClick,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("reply_button_${message.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Reply,
                                contentDescription = "Reply to message",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlockView(
    language: String,
    code: String,
    accentColor: Color
) {
    val context = LocalContext.current
    val displayLang = if (language.isNotBlank()) language.uppercase() else "CODE"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CodeBackground)
            .border(1.dp, CodeBorder, RoundedCornerShape(12.dp))
    ) {
        // Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CodeHeader)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = displayLang,
                    color = CodeText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Code", code)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Code",
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Code Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = code.trim(),
                color = CodeText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
        }
    }
}

private fun parseMessageContent(raw: String): List<MessageContentChunk> {
    val chunks = mutableListOf<MessageContentChunk>()
    val parts = raw.split("```")

    for (i in parts.indices) {
        val part = parts[i]
        if (i % 2 == 1) {
            // Inside code block
            val lines = part.split("\n", limit = 2)
            val lang = if (lines.size > 1 && !lines[0].contains(" ")) lines[0].trim() else ""
            val code = if (lines.size > 1) lines[1] else part
            chunks.add(MessageContentChunk.CodeChunk(lang, code))
        } else {
            // Normal text
            if (part.isNotBlank()) {
                chunks.add(MessageContentChunk.TextChunk(part))
            }
        }
    }

    if (chunks.isEmpty()) {
        chunks.add(MessageContentChunk.TextChunk(raw))
    }

    return chunks
}

@Composable
fun BuilderChatStatus(
    status: String,
    statusText: String,
    iteration: Int,
    accentColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "✨ MAYA AUTONOMOUS BUILDER",
                    color = accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (status) {
                    "PLANNING" -> "Planning..."
                    "GENERATING" -> "Creating..."
                    "BUILDING" -> "Building..."
                    "FIXING" -> "Fixing... (Iteration $iteration/5)"
                    "TESTING" -> "Testing..."
                    "SUCCESS" -> "Build complete ✓"
                    "FAILED" -> "Build failed ⚠"
                    else -> status
                },
                color = when (status) {
                    "SUCCESS" -> Color.Green
                    "FAILED" -> Color.Red
                    else -> TextPrimary
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                color = TextSecondary,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            val progress = when (status) {
                "PLANNING" -> 0.15f
                "GENERATING" -> 0.40f
                "BUILDING" -> 0.65f
                "TESTING" -> 0.85f
                "SUCCESS" -> 1.0f
                "FAILED" -> 1.0f
                else -> 0.5f
            }
            LinearProgressIndicator(
                progress = progress,
                color = if (status == "FAILED") Color.Red else if (status == "SUCCESS") Color.Green else accentColor,
                trackColor = DarkBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }
    }
}
