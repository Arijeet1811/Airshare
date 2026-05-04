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
                    val width = size.width
                    val height = size.height
                    val center = Offset(width / 2f, height / 2f)
                    val maxRadius = Math.min(width, height) / 2.5f

                    peers.forEachIndexed { index, peer ->
                        val angle = (index * 137.5f) * (Math.PI / 180f)
                        val normalizedRssi = (peer.rssi.coerceIn(-100, -30) + 100) / 70f
                        val radius = maxRadius * (1f - normalizedRssi * 0.8f).coerceIn(0.2f, 0.9f)
                        
                        val px = center.x + radius * cos(angle).toFloat()
                        val py = center.y + radius * sin(angle).toFloat()

                        val distance = sqrt((offset.x - px) * (offset.x - px) + (offset.y - py) * (offset.y - py))
                        if (distance <= 44.dp.toPx()) { // Increased touch target to 44dp for accessibility
                            onPeerTapped(peer)
                        }
                    }
                }
            }
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2.5f

        // Draw static circles
        drawCircle(
            color = Color.Green.copy(alpha = 0.1f),
            radius = maxRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color.Green.copy(alpha = 0.1f),
            radius = maxRadius * 0.66f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color.Green.copy(alpha = 0.1f),
            radius = maxRadius * 0.33f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        // Draw pulsing circles
        drawCircle(
            color = Color.Green.copy(alpha = (1f - pulse1.value) * 0.3f),
            radius = maxRadius * pulse1.value,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = Color.Green.copy(alpha = (1f - pulse2.value) * 0.3f),
            radius = maxRadius * pulse2.value,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Draw center point
        drawCircle(
            color = Color.Green,
            radius = 8.dp.toPx(),
            center = center
        )

        // Draw Peers as bright green dots
        peers.forEachIndexed { index, peer ->
            // Distribute peers around the radar based on their index and RSSI
            val angle = (index * 137.5f) * (Math.PI / 180f) // Golden angle distribution
            // Map RSSI (-100 to -30) to radius (max to min)
            val normalizedRssi = (peer.rssi.coerceIn(-100, -30) + 100) / 70f
            val radius = maxRadius * (1f - normalizedRssi * 0.8f).coerceIn(0.2f, 0.9f)
            
            val x = center.x + radius * cos(angle).toFloat()
            val y = center.y + radius * sin(angle).toFloat()

            drawCircle(
                color = Color.Green,
                radius = 6.dp.toPx(),
                center = Offset(x, y)
            )
            
            // Draw name below dot
            drawContext.canvas.nativeCanvas.drawText(
                peer.name,
                x,
                y + 22 + 6.dp.toPx(), // Adjust Y: 22px below dot center plus radius
                paint
            )
            
            // Halo for triggered peers
            if (peer.isProximityTriggered) {
                drawCircle(
                    color = Color.Green.copy(alpha = 0.4f),
                    radius = 12.dp.toPx(),
                    center = Offset(x, y),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
