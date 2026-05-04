package com.airshare.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.airshare.app.model.Peer
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarView(
    peers: List<Peer>,
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

    Canvas(modifier = modifier.fillMaxSize()) {
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
