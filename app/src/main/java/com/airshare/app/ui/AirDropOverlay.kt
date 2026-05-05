package com.airshare.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airshare.app.model.Peer

@Composable
fun AirDropOverlay(
    peer: Peer?,
    isSender: Boolean = false,
    fileCount: Int = 0,
    totalSizeBytes: Long = 0L,
    onDismiss: () -> Unit,
    onAccept: (Peer) -> Unit
) {
    AnimatedVisibility(
        visible = peer != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        if (peer != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .statusBarsPadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.95f),
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(32.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.02f))
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SeaWaveAnimation()

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = if (isSender) "Connecting to ${peer.name}" else peer.name,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )

                        val sizeText = when {
                            totalSizeBytes >= 1_000_000L -> "%.1f MB".format(totalSizeBytes / 1_000_000f)
                            totalSizeBytes >= 1_000L -> "%.1f KB".format(totalSizeBytes / 1_000f)
                            else -> "$totalSizeBytes B"
                        }
                        
                        Text(
                            text = if (isSender) "Preparing artifacts for transfer..." 
                                   else "Ready to receive $fileCount file${if (fileCount != 1) "s" else ""} • $sizeText",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        if (isSender) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color(0xFF34C759),
                                    strokeWidth = 2.dp
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            TextButton(onClick = onDismiss) {
                                Text("Abort", color = Color.White.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(0.4f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.05f)
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(vertical = 12.dp)
                                ) {
                                    Text("Decline", color = Color.White.copy(alpha = 0.7f))
                                }

                                Button(
                                    onClick = { onAccept(peer) },
                                    modifier = Modifier.weight(0.6f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF34C759)
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(vertical = 12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Accept", color = Color.White, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeaWaveAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "SeaWave")
    
    Box(
        modifier = Modifier
            .size(100.dp),
        contentAlignment = Alignment.Center
    ) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2500, delayMillis = index * 800, easing = EaseOutQuart),
                    repeatMode = RepeatMode.Restart
                ), label = "WaveScale"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 0.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2500, delayMillis = index * 800, easing = EaseOutQuart),
                    repeatMode = RepeatMode.Restart
                ), label = "WaveAlpha"
            )
            Box(
                modifier = Modifier
                    .size(100.dp * scale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF34C759).copy(alpha = alpha), Color.Transparent)
                        ),
                        shape = RoundedCornerShape(50.dp)
                    )
                    .border(
                        width = (0.5.dp / scale).coerceAtLeast(0.1.dp),
                        color = Color(0xFF34C759).copy(alpha = alpha * 0.5f),
                        shape = RoundedCornerShape(50.dp)
                    )
            )
        }
        
        // Center icon with glow
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF34C759), Color(0xFF16A34A))
                    ),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
