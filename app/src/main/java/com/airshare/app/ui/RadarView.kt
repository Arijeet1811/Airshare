package com.airshare.app.ui

import android.graphics.Paint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.airshare.app.model.Peer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun RadarView(
    peers: List<Peer>,
    onPeerTapped: (Peer) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    
    // Pulse animations
    val pulse1 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "pulse1"
    )
    
    val pulse2 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "pulse2"
    )

    val paint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 28f
            isAntiAlias = true
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(peers) {
                detectTapGestures { offset ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.minDimension / 2.2f

                    peers.forEachIndexed { index, peer ->
                        val angle = (index * 137.5f) * (Math.PI / 180f)
                        val normalizedRssi = (peer.rssi.coerceIn(-100, -30) + 100) / 70f
                        val radius = maxRadius * (1f - normalizedRssi * 0.7f).coerceIn(0.25f, 0.95f)
                        
                        val px = center.x + radius * cos(angle).toFloat()
                        val py = center.y + radius * sin(angle).toFloat()

                        val distance = sqrt((offset.x - px) * (offset.x - px) + (offset.y - py) * (offset.y - py))
                        if (distance <= 48.dp.toPx()) {
                            onPeerTapped(peer)
                        }
                    }
                }
            }
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2.2f

        // Draw static circles with gradient stroke
        val radarColor = Color(0xFF34C759).copy(alpha = 0.15f)
        
        repeat(4) { i ->
            drawCircle(
                color = radarColor,
                radius = maxRadius * (1f - i * 0.25f),
                center = center,
                style = Stroke(width = 0.5.dp.toPx())
            )
        }

        // Draw pulsing circles
        drawCircle(
            color = Color(0xFF34C759).copy(alpha = (1f - pulse1.value) * 0.2f),
            radius = maxRadius * pulse1.value,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color(0xFF34C759).copy(alpha = (1f - pulse2.value) * 0.2f),
            radius = maxRadius * pulse2.value,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        // Draw center "Me" indicator
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(Color(0xFF34C759), Color(0xFF22C55E).copy(alpha = 0f)),
                center = center,
                radius = 24.dp.toPx()
            ),
            radius = 24.dp.toPx(),
            center = center
        )
        drawCircle(
            color = Color.White,
            radius = 4.dp.toPx(),
            center = center
        )

        // Draw "Looking for devices" message if list is empty
        if (peers.isEmpty()) {
            paint.textSize = 30f
            paint.color = android.graphics.Color.GRAY
            paint.alpha = (127 * (1f - pulse1.value)).toInt()
            drawContext.canvas.nativeCanvas.drawText(
                "Looking for artifacts...",
                center.x,
                center.y + maxRadius + 40.dp.toPx(),
                paint
            )
        }

        // Draw Peers as avatars
        peers.forEachIndexed { index, peer ->
            val angle = (index * 137.5f) * (Math.PI / 180f)
            val normalizedRssi = (peer.rssi.coerceIn(-100, -30) + 100) / 70f
            val radius = maxRadius * (1f - normalizedRssi * 0.7f).coerceIn(0.25f, 0.95f)
            
            val x = center.x + radius * cos(angle).toFloat()
            val y = center.y + radius * sin(angle).toFloat()

            // Avatar background
            val avatarRadius = 24.dp.toPx()
            
            // Proximity highlight
            if (peer.isProximityTriggered) {
                drawCircle(
                    color = Color(0xFF34C759).copy(alpha = 0.3f),
                    radius = avatarRadius + 8.dp.toPx(),
                    center = Offset(x, y)
                )
            }

            drawCircle(
                color = Color.White.copy(alpha = 0.15f),
                radius = avatarRadius,
                center = Offset(x, y)
            )
            
            drawCircle(
                color = Color.White,
                radius = avatarRadius,
                center = Offset(x, y),
                style = Stroke(width = 1.dp.toPx())
            )

            // Initial for avatar
            val initial = peer.name.take(1).uppercase()
            paint.textSize = 36f
            paint.color = android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(
                initial,
                x,
                y + 12f,
                paint
            )
            
            // Name below
            paint.textSize = 24f
            paint.color = android.graphics.Color.GRAY
            drawContext.canvas.nativeCanvas.drawText(
                peer.name,
                x,
                y + avatarRadius + 24f,
                paint
            )
        }
    }
}
