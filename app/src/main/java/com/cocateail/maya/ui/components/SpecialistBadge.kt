package com.cocateail.maya.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocateail.maya.data.specialist.SpecialistType
import com.cocateail.maya.ui.theme.*

@Composable
fun SpecialistBadge(
    specialistType: SpecialistType,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    if (specialistType == SpecialistType.GENERAL) return

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val (title, iconStr, gradientColors) = when (specialistType) {
            SpecialistType.WEB_DEV -> Triple(
                "WEB DEV",
                "🌐",
                listOf(Color(0xFF00C6FF), Color(0xFF0072FF))
            )
            SpecialistType.APP_DEV -> Triple(
                "APP DEV",
                "📱",
                listOf(Color(0xFF11998E), Color(0xFF38EF7D))
            )
            SpecialistType.GAME_DEV -> Triple(
                "GAME DEV",
                "🎮",
                listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))
            )
            SpecialistType.GENERAL -> Triple(
                "GENERAL",
                "✨",
                listOf(Color(0xFF888888), Color(0xFF555555))
            )
        }

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(gradientColors.map { it.copy(alpha = 0.2f) }))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(gradientColors),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = iconStr, fontSize = 11.sp)
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}
