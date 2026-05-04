package com.airshare.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.85f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Custom Wave Animation Placeholder (Simulation of Sea Wave)
                        SeaWaveAnimation()

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = peer.name,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "would like to share files",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Decline")
                            }

                            Button(
                                onClick = { onAccept(peer) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Accept", color = Color.White)
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
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF007AFF).copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        // Simple procedural wave representation using pulsing bars or circles
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, delayMillis = index * 400),
                    repeatMode = RepeatMode.Reverse
                ), label = "WaveScale"
            )
            androidx.compose.foundation.border(
                width = 2.dp,
                color = Color(0xFF007AFF).copy(alpha = 1f - (scale - 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ).let {
                Box(modifier = Modifier.size(64.dp * scale).then(it))
            }
        }
    }
}
