package com.devin.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.devin.messenger.data.UserPublic
import kotlin.math.abs

@Composable
fun Avatar(
    user: UserPublic?,
    fullUrl: String?,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val initials = (user?.displayName ?: user?.username ?: "?")
        .trim()
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifBlank { "?" }

    val seed = user?.id?.toInt() ?: user?.username?.hashCode() ?: 0
    val gradient = gradientFor(seed)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(brush = gradient),
        contentAlignment = Alignment.Center,
    ) {
        if (fullUrl != null) {
            AsyncImage(
                model = fullUrl,
                contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Text(
                text = initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value / 2.4f).sp,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun gradientFor(seed: Int): Brush {
    val palettes = listOf(
        Color(0xFF7C8CFF) to Color(0xFFFF7AC6),
        Color(0xFF34D399) to Color(0xFF60A5FA),
        Color(0xFFF59E0B) to Color(0xFFEF4444),
        Color(0xFFA855F7) to Color(0xFF22D3EE),
        Color(0xFFFB7185) to Color(0xFFFBBF24),
        Color(0xFF22C55E) to Color(0xFF06B6D4),
    )
    val p = palettes[abs(seed) % palettes.size]
    return Brush.linearGradient(listOf(p.first, p.second))
}
