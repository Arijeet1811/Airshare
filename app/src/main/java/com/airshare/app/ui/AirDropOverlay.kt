package com.airshare.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airshare.app.model.Peer
import kotlin.math.sin

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
        enter = slideInVertically(
            initialOffsetY = { -it - 200 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(
            targetOffsetY = { -it - 200 },
            animationSpec = tween(250, easing = EaseOutExpo)
        ) + fadeOut(animationSpec = tween(200))
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
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OceanWaveAnimation()

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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.12f)
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text("Decline", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                                Button(
                                    onClick = { onAccept(peer) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
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
fun OceanWaveAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "OceanWave")
    
    // Wave phase animation — drives the sine wave movement
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "WavePhase"
    )
    
    // Second wave at different speed for depth effect
    val wavePhase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "WavePhase2"
    )
    
    // Foam alpha — the white crest of the wave
    val foamAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "FoamAlpha"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        val width = size.width
        val height = size.height
        val midY = height * 0.55f
        val amplitude = height * 0.18f
        val amplitude2 = height * 0.12f

        // Draw ocean background gradient
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0077B6),
                    Color(0xFF023E8A)
                )
            )
        )

        // Draw first wave (back layer — darker blue)
        val path1 = Path()
        path1.moveTo(0f, height)
        for (x in 0..width.toInt() step 2) {
            val y = midY + amplitude * sin(wavePhase + x * 0.015f).toFloat()
            path1.lineTo(x.toFloat(), y)
        }
        path1.lineTo(width, height)
        path1.close()
        drawPath(
            path = path1,
            color = Color(0xFF0096C7).copy(alpha = 0.8f)
        )

        // Draw second wave (front layer — lighter blue)
        val path2 = Path()
        path2.moveTo(0f, height)
        for (x in 0..width.toInt() step 2) {
            val y = midY + amplitude2 * sin(wavePhase2 + x * 0.02f + 1f).toFloat() + amplitude * 0.3f
            path2.lineTo(x.toFloat(), y)
        }
        path2.lineTo(width, height)
        path2.close()
        drawPath(
            path = path2,
            color = Color(0xFF48CAE4).copy(alpha = 0.6f)
        )

        // Draw foam (white crests)
        for (x in 0..width.toInt() step 4) {
            val y = midY + amplitude * sin(wavePhase + x * 0.015f).toFloat()
            // Small white dots/dashes at wave peaks
            if (sin(wavePhase + x * 0.015f).toFloat() < -0.5f) {
                drawCircle(
                    color = Color.White.copy(alpha = foamAlpha),
                    radius = 3f,
                    center = Offset(x.toFloat(), y - 4f)
                )
            }
        }
    }
}
