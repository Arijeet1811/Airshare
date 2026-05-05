package com.airshare.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SeaWaveAnimation()

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = peer.name,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )

                        val sizeText = when {
                            totalSizeBytes >= 1_000_000L -> "%.1f MB".format(totalSizeBytes / 1_000_000f)
                            totalSizeBytes >= 1_000L -> "%.1f KB".format(totalSizeBytes / 1_000f)
                            else -> "$totalSizeBytes B"
                        }
                        
                        Text(
                            text = if (isSender) "Waiting for response..." 
                                   else "Received $fileCount file${if (fileCount != 1) "s" else ""} • $sizeText",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        if (isSender) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = onDismiss) {
                                Text("Cancel", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.1f)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Decline", color = Color.White)
                                }

                                Button(
                                    onClick = { onAccept(peer) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF34C759)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Accept", color = Color.White, fontWeight = FontWeight.Bold)
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
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = java.util.concurrent.TimeUnit.SECONDS.toMillis(2).let { tween(it.toInt(), easing = LinearEasing) }
        ), label = "WaveOffset"
    )

    Box(
        modifier = Modifier
            .size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, delayMillis = index * 600, easing = EaseOutExpo),
                    repeatMode = RepeatMode.Restart
                ), label = "WaveScale"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, delayMillis = index * 600, easing = EaseOutExpo),
                    repeatMode = RepeatMode.Restart
                ), label = "WaveAlpha"
            )
            Box(
                modifier = Modifier
                    .size(80.dp * scale)
                    .border(
                        width = 1.5.dp,
                        color = Color(0xFF34C759).copy(alpha = alpha),
                        shape = RoundedCornerShape(40.dp)
                    )
            )
        }
        
        // Center icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF34C759), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Share,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
