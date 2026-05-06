package com.airshare.app.ui

import android.graphics.Paint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.airshare.app.model.Peer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PeerAnimationState(val peerId: String) {
    val scale = Animatable(0f)
    val alpha = Animatable(0f)
    val x = Animatable(0f)
    val y = Animatable(0f)
    var initialized = false
}

@Composable
fun RadarView(
    peers: List<Peer>,
    onPeerTapped: (Peer) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    
    // Smooth pulse animation
    val pulseValue by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseOutExpo),
            repeatMode = RepeatMode.Restart
        ), label = "pulse"
    )

    // Rotating scanner beam
    val scanRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "scanRotation"
    )

    // Animation states for peers
    val peerStates = remember { mutableStateMapOf<String, PeerAnimationState>() }

    // Sync peer states with peers list
    LaunchedEffect(peers) {
        val currentIds = peers.map { it.id }.toSet()
        // Remove old
        peerStates.keys.retainAll { it in currentIds }
        // Add new
        peers.forEach { peer ->
            if (peer.id !in peerStates) {
                val state = PeerAnimationState(peer.id)
                peerStates[peer.id] = state
                // Animate entrance
                state.scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                )
                state.alpha.animateTo(1f, animationSpec = tween(500))
            }
        }
    }

    val paint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 28f
            isAntiAlias = true
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val center = Offset(widthPx / 2f, heightPx / 2f)
        val maxRadius = minOf(widthPx, heightPx) / 2.2f

        // OUTSIDE Canvas — composable effects to handle animations
        peers.forEachIndexed { index, peer ->
            val state = peerStates[peer.id] ?: return@forEachIndexed
            
            val angle = (index * 137.5f) * (Math.PI / 180f)
            val normalizedRssi = (peer.rssi.coerceIn(-100, -30) + 100) / 70f
            val radius = maxRadius * (1f - normalizedRssi * 0.7f).coerceIn(0.25f, 0.95f)
            
            val targetX = center.x + radius * cos(angle).toFloat()
            val targetY = center.y + radius * sin(angle).toFloat()

            LaunchedEffect(targetX, targetY) {
                if (!state.initialized) {
                    state.x.snapTo(targetX)
                    state.y.snapTo(targetY)
                    state.initialized = true
                } else {
                    launch {
                        state.x.animateTo(
                            targetX,
                            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow)
                        )
                    }
                    launch {
                        state.y.animateTo(
                            targetY,
                            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow)
                        )
                    }
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(peers) {
                    detectTapGestures { offset ->
                        peers.forEachIndexed { index, peer ->
                            val angle = (index * 137.5f) * (Math.PI / 180f)
                            val normalizedRssi = (peer.rssi.coerceIn(-100, -30) + 100) / 70f
                            val radius = maxRadius * (1f - normalizedRssi * 0.7f).coerceIn(0.25f, 0.95f)
                            
                            val px = center.x + radius * cos(angle).toFloat()
                            val py = center.y + radius * sin(angle).toFloat()

                            val distance = sqrt((offset.x - px) * (offset.x - px) + (offset.y - py) * (offset.y - py))
                            if (distance <= 48.dp.toPx()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPeerTapped(peer)
                            }
                        }
                    }
                }
        ) {
            // INSIDE Canvas — only draw calls
            // Draw rotating scanner beam
            drawArc(
                brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                    colors = listOf(Color.Transparent, Color(0xFF34C759).copy(alpha = 0.2f), Color.Transparent),
                    center = center
                ),
                startAngle = scanRotation - 45f,
                sweepAngle = 45f,
                useCenter = true,
                topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                size = Size(maxRadius * 2, maxRadius * 2)
            )

            // Draw dot-matrix grid
            val dotColor = Color.White.copy(alpha = 0.05f)
            val spacing = 24.dp.toPx()
            for (x in 0..(size.width / spacing).toInt()) {
                for (y in 0..(size.height / spacing).toInt()) {
                    drawCircle(
                        color = dotColor,
                        radius = 1.dp.toPx(),
                        center = Offset(x * spacing, y * spacing)
                    )
                }
            }

            // Draw static circles
            val radarColor = Color(0xFF34C759).copy(alpha = 0.1f)
            repeat(4) { i ->
                drawCircle(
                    color = radarColor,
                    radius = maxRadius * (1f - i * 0.25f),
                    center = center,
                    style = Stroke(width = 0.5.dp.toPx())
                )
            }

            // Smoother pulsing circles
            drawCircle(
                color = Color(0xFF34C759).copy(alpha = (1f - pulseValue) * 0.15f),
                radius = maxRadius * pulseValue,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            
            val delayedPulse = (pulseValue + 0.5f) % 1f
            drawCircle(
                color = Color(0xFF34C759).copy(alpha = (1f - delayedPulse) * 0.1f),
                radius = maxRadius * delayedPulse,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Center indicator
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(Color(0xFF34C759), Color(0xFF22C55E).copy(alpha = 0f)),
                    center = center,
                    radius = 32.dp.toPx()
                ),
                radius = 32.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = center
            )

            // Draw Look message
            if (peers.isEmpty()) {
                paint.textSize = 30f
                paint.color = android.graphics.Color.GRAY
                paint.alpha = (127 * (1f - pulseValue)).toInt()
                drawContext.canvas.nativeCanvas.drawText(
                    "Scanning proximity field...",
                    center.x,
                    center.y + maxRadius + 40.dp.toPx(),
                    paint
                )
            }

            // Draw animated Peers
            peers.forEachIndexed { _, peer ->
                val state = peerStates[peer.id] ?: return@forEachIndexed
                
                val x = state.x.value
                val y = state.y.value
                val s = state.scale.value
                val a = state.alpha.value
                
                val avatarRadius = 24.dp.toPx() * s
                
                if (peer.isProximityTriggered) {
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(Color(0xFF34C759).copy(alpha = 0.4f * a), Color.Transparent),
                            center = Offset(x, y),
                            radius = avatarRadius + 16.dp.toPx()
                        ),
                        radius = avatarRadius + 16.dp.toPx(),
                        center = Offset(x, y)
                    )
                }

                drawCircle(
                    color = Color.White.copy(alpha = 0.1f * a),
                    radius = avatarRadius,
                    center = Offset(x, y)
                )
                
                drawCircle(
                    color = Color.White.copy(alpha = a),
                    radius = avatarRadius,
                    center = Offset(x, y),
                    style = Stroke(width = 1.dp.toPx())
                )

                // Initial
                if (s > 0.5f) {
                    val initial = peer.name.take(1).uppercase()
                    paint.textSize = 36f * s
                    paint.color = android.graphics.Color.WHITE
                    paint.alpha = (255 * a).toInt()
                    drawContext.canvas.nativeCanvas.drawText(initial, x, y + 12f * s, paint)
                    
                    // Name
                    paint.textSize = 24f * s
                    paint.color = android.graphics.Color.GRAY
                    paint.alpha = (200 * a).toInt()
                    drawContext.canvas.nativeCanvas.drawText(peer.name, x, y + avatarRadius + 24f * s, paint)
                }
            }
        }
    }
}
