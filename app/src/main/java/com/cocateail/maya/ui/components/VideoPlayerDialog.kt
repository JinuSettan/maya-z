package com.cocateail.maya.ui.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cocateail.maya.ui.theme.DarkCard
import com.cocateail.maya.ui.theme.DarkSurface
import com.cocateail.maya.ui.theme.TextPrimary
import com.cocateail.maya.ui.theme.TextSecondary

@Composable
fun VideoPlayerDialog(
    videoUrl: String,
    promptTitle: String,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp)),
            color = DarkSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🎬 MAYA VIDEO PLAYER",
                            color = accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = promptTitle.ifBlank { "AI Generated Video" },
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Player",
                            tint = TextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Video Box with Native VideoView and WebView Fallback
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkCard),
                    contentAlignment = Alignment.Center
                ) {
                    var isVideoViewFailed by remember { mutableStateOf(false) }

                    if (!isVideoViewFailed && videoUrl.endsWith(".mp4", ignoreCase = true)) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val mc = MediaController(ctx)
                                    mc.setAnchorView(this)
                                    setMediaController(mc)
                                    setVideoURI(Uri.parse(videoUrl))
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        isVideoViewFailed = true
                                        true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // High-performance Compose AsyncImage with WebView Software Layer Fallback
                        var isWebFallback by remember { mutableStateOf(false) }

                        if (!isWebFallback) {
                            coil.compose.AsyncImage(
                                model = videoUrl,
                                contentDescription = "AI Generated Video Stream",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                onError = {
                                    isWebFallback = true
                                }
                            )
                        } else {
                            AndroidView(
                                factory = { ctx ->
                                    android.webkit.WebView(ctx).apply {
                                        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                                        settings.javaScriptEnabled = true
                                        settings.mediaPlaybackRequiresUserGesture = false
                                        settings.loadWithOverviewMode = true
                                        settings.useWideViewPort = true
                                        settings.domStorageEnabled = true
                                        webChromeClient = android.webkit.WebChromeClient()
                                        webViewClient = android.webkit.WebViewClient()
                                        loadDataWithBaseURL(
                                            null,
                                            """
                                            <!DOCTYPE html>
                                            <html>
                                            <head>
                                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                            <style>
                                            body { margin:0; padding:0; background:#0F172A; display:flex; align-items:center; justify-content:center; height:100vh; overflow:hidden; }
                                            img, video { max-width:100%; max-height:100%; object-fit:cover; border-radius:12px; }
                                            </style>
                                            </head>
                                            <body>
                                            <img src="$videoUrl" alt="AI Video Stream" />
                                            </body>
                                            </html>
                                            """.trimIndent(),
                                            "text/html",
                                            "UTF-8",
                                            null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        com.cocateail.maya.util.MediaDownloadHelper.downloadToGallery(
                            context = context,
                            mediaUrl = videoUrl,
                            isVideo = true,
                            customTitle = promptTitle
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download Video",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Download Video to Gallery",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Touch screen for play, pause & seek controls",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}
