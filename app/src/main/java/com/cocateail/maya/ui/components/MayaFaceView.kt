package com.cocateail.maya.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cocateail.maya.ui.theme.DarkBorder
import com.cocateail.maya.ui.theme.DarkCard
import com.cocateail.maya.ui.theme.PurpleAccent
import com.cocateail.maya.ui.theme.VermilionPrimary
import kotlinx.coroutines.delay

enum class FaceMode {
    IDLE, THINKING, SPEAKING, HAPPY, CONFUSED, SAD, OFFLINE
}

@Composable
fun MayaFaceView(
    mode: FaceMode,
    enabled: Boolean = true,
    size: Dp = 64.dp,
    accentColor: Color = VermilionPrimary,
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    // Bobbing animation
    val infiniteTransition = rememberInfiniteTransition(label = "maya_face_bob")
    val bobOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = when (mode) {
            FaceMode.THINKING -> -4f
            FaceMode.SPEAKING -> 2f
            FaceMode.HAPPY -> -3f
            FaceMode.OFFLINE -> 0f
            else -> 1.5f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mode) {
                    FaceMode.THINKING -> 500
                    FaceMode.SPEAKING -> 350
                    FaceMode.HAPPY -> 600
                    else -> 1400
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bob_offset"
    )

    // Pulsing aura scale for Thinking & Speaking
    val auraScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (mode == FaceMode.THINKING || mode == FaceMode.SPEAKING) 1.15f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (mode == FaceMode.THINKING) 600 else 800,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_scale"
    )

    // Mouth jaw animation when speaking
    val mouthSpeakHeight by infiniteTransition.animateFloat(
        initialValue = 3f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(180, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speak_height"
    )

    // Blinking
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, mode) {
        while (enabled && mode != FaceMode.OFFLINE) {
            delay(3200)
            isBlinking = true
            delay(120)
            isBlinking = false
        }
    }

    // Eye look movement
    var lookOffsetX by remember { mutableStateOf(0.dp) }
    var lookOffsetY by remember { mutableStateOf(0.dp) }
    LaunchedEffect(enabled, mode) {
        while (enabled && mode != FaceMode.OFFLINE) {
            delay(4200)
            when (mode) {
                FaceMode.THINKING -> {
                    lookOffsetX = 1.5.dp
                    lookOffsetY = (-2).dp
                }
                FaceMode.CONFUSED -> {
                    lookOffsetX = (-2).dp
                    lookOffsetY = 1.dp
                }
                else -> {
                    lookOffsetX = if (lookOffsetX == 0.dp) 1.5.dp else 0.dp
                    lookOffsetY = 0.dp
                }
            }
        }
    }

    // Head tilt angle
    val headRotation by animateFloatAsState(
        targetValue = when (mode) {
            FaceMode.CONFUSED -> 8f
            FaceMode.THINKING -> -5f
            FaceMode.SAD -> -3f
            FaceMode.HAPPY -> 3f
            else -> 0f
        },
        animationSpec = tween(300),
        label = "head_rotation"
    )

    val currentMouthHeight = when (mode) {
        FaceMode.SPEAKING -> mouthSpeakHeight.dp
        FaceMode.THINKING -> 5.dp
        FaceMode.HAPPY -> 8.dp
        FaceMode.SAD -> 3.dp
        FaceMode.CONFUSED -> 4.dp
        FaceMode.OFFLINE -> 2.dp
        FaceMode.IDLE -> 5.dp
    }

    Box(
        modifier = modifier
            .size(size)
            .testTag("maya_face_view"),
        contentAlignment = Alignment.Center
    ) {
        // Outer Glowing Aura Ring
        if (mode == FaceMode.THINKING || mode == FaceMode.SPEAKING || mode == FaceMode.HAPPY) {
            Box(
                modifier = Modifier
                    .size(size * 0.95f)
                    .scale(auraScale)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = if (mode == FaceMode.THINKING) 0.35f else 0.25f))
            )
        }

        // Main Character Head Container
        Box(
            modifier = Modifier
                .offset(y = bobOffset.dp)
                .rotate(headRotation)
                .size(size * 0.82f)
                .clip(CircleShape)
                .background(DarkCard)
                .border(
                    width = 1.5.dp,
                    color = if (mode == FaceMode.OFFLINE) DarkBorder else accentColor.copy(alpha = 0.8f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            // Hair bangs top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size * 0.24f)
                    .padding(horizontal = 4.dp)
                    .background(
                        color = Color(0xFFE2E6F0).copy(alpha = 0.9f),
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 20.dp)
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = size * 0.26f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Eyebrows Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(size * 0.22f),
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    val browColor = if (mode == FaceMode.OFFLINE) Color.Gray else accentColor

                    // Left Eyebrow
                    Box(
                        modifier = Modifier
                            .width(size * 0.16f)
                            .height(2.dp)
                            .rotate(
                                when (mode) {
                                    FaceMode.THINKING -> -12f
                                    FaceMode.CONFUSED -> -18f
                                    FaceMode.SAD -> 12f
                                    FaceMode.HAPPY -> -5f
                                    else -> 0f
                                }
                            )
                            .background(browColor, RoundedCornerShape(1.dp))
                    )

                    // Right Eyebrow
                    Box(
                        modifier = Modifier
                            .width(size * 0.16f)
                            .height(2.dp)
                            .rotate(
                                when (mode) {
                                    FaceMode.THINKING -> 12f
                                    FaceMode.CONFUSED -> 8f
                                    FaceMode.SAD -> -12f
                                    FaceMode.HAPPY -> 5f
                                    else -> 0f
                                }
                            )
                            .background(browColor, RoundedCornerShape(1.dp))
                    )
                }

                // Eyes Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(size * 0.18f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val eyeScaleY = if (isBlinking || mode == FaceMode.OFFLINE) 0.12f else 1.0f

                    // Left Eye
                    Box(
                        modifier = Modifier
                            .width(size * 0.15f)
                            .height(size * (0.2f * eyeScaleY))
                            .clip(CircleShape)
                            .background(Color(0xFFF7F8FA)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!isBlinking && mode != FaceMode.OFFLINE) {
                            Box(
                                modifier = Modifier
                                    .offset(x = lookOffsetX, y = lookOffsetY)
                                    .size(size * 0.08f, size * 0.12f)
                                    .clip(CircleShape)
                                    .background(if (mode == FaceMode.OFFLINE) Color.Gray else accentColor)
                            )
                        }
                    }

                    // Right Eye
                    Box(
                        modifier = Modifier
                            .width(size * 0.15f)
                            .height(size * (0.2f * eyeScaleY))
                            .clip(CircleShape)
                            .background(Color(0xFFF7F8FA)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!isBlinking && mode != FaceMode.OFFLINE) {
                            Box(
                                modifier = Modifier
                                    .offset(x = lookOffsetX, y = lookOffsetY)
                                    .size(size * 0.08f, size * 0.12f)
                                    .clip(CircleShape)
                                    .background(if (mode == FaceMode.OFFLINE) Color.Gray else accentColor)
                            )
                        }
                    }
                }

                // Rosy Blush Cheeks
                if (mode == FaceMode.HAPPY || mode == FaceMode.IDLE || mode == FaceMode.SPEAKING) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(size * 0.32f),
                        modifier = Modifier.padding(top = 1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(size * 0.08f)
                                .clip(CircleShape)
                                .background(Color(0xFFFF8A9E).copy(alpha = 0.5f))
                        )
                        Box(
                            modifier = Modifier
                                .size(size * 0.08f)
                                .clip(CircleShape)
                                .background(Color(0xFFFF8A9E).copy(alpha = 0.5f))
                        )
                    }
                }

                // Nose
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(2.dp, 3.dp)
                        .background(DarkBorder, RoundedCornerShape(1.dp))
                )

                // Mouth
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .width(
                            when (mode) {
                                FaceMode.HAPPY -> size * 0.28f
                                FaceMode.SPEAKING -> size * 0.24f
                                FaceMode.THINKING -> size * 0.14f
                                else -> size * 0.22f
                            }
                        )
                        .height(currentMouthHeight)
                        .border(
                            width = 1.5.dp,
                            color = if (mode == FaceMode.OFFLINE) Color.Gray else accentColor,
                            shape = if (mode == FaceMode.THINKING) CircleShape else RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                        )
                )
            }
        }
    }
}
