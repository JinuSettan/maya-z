package com.cocateail.maya.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocateail.maya.ui.theme.*

@Composable
fun BrainSetupView(
    progress: Float,
    isReady: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onStart: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(VermilionPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = if (isReady) "MAYA is ready. ✨" else "Welcome to MAYA 👋",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isReady) "Let's talk." else if (error != null) "We need one more try." else "Preparing your AI companion...",
                color = TextSecondary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (!isReady && error == null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(8.dp)
                        .clip(CircleShape),
                    color = VermilionPrimary,
                    trackColor = DarkCard
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = VermilionPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Initializing neural weights & speech engine...",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }

            if (error != null) {
                Text(
                    text = "Check connection or config. Conversations stay safely on this device.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VermilionPrimary,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("retry_brain_setup")
                ) {
                    Text("Try again", fontWeight = FontWeight.Bold)
                }
            }

            if (isReady) {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VermilionPrimary,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(50.dp)
                        .testTag("start_conversation_button")
                ) {
                    Text("Start Talking", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
