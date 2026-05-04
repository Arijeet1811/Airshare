package com.airshare.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airshare.app.model.Peer
import com.airshare.app.service.AirShareService
import com.airshare.app.ui.AirDropOverlay
import com.airshare.app.ui.RadarView
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var airShareService: AirShareService? = null
    private var isBound by mutableStateOf(false)

    private val permissionsToRequest = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_NEARBY_DEVICE)
        }
        
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startAirShareService()
        } else {
            Toast.makeText(this, "Permissions required for AirShare", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AirShareService.LocalBinder
            airShareService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            airShareService = null
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, AirShareService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private var transferProgress by mutableStateOf<Float?>(null)
    private var isSuccess by mutableStateOf(false)
    private var selectedUris by mutableStateOf<List<android.net.Uri>>(emptyList())

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = uris
            Toast.makeText(this, "Selected ${uris.size} files", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val peers by if (isBound) {
                airShareService?.getDiscoveredPeers()?.collectAsState() ?: mutableStateOf(emptyList())
            } else {
                remember { mutableStateOf(emptyList<Peer>()) }
            }

            val triggeredPeer = peers.find { it.isProximityTriggered }
            var dismissedPeerId by remember { mutableStateOf<String?>(null) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        RadarView(peers = peers)

                        // File Selection Button
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 60.dp)
                        ) {
                            Button(
                                onClick = { filePickerLauncher.launch("*/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Select Files to Share", color = Color.White)
                            }
                        }
                        
                        AirDropOverlay(
                            peer = if (triggeredPeer?.id != dismissedPeerId) triggeredPeer else null,
                            onDismiss = { dismissedPeerId = triggeredPeer?.id },
                            onAccept = { peer ->
                                if (selectedUris.isNotEmpty()) {
                                    simulateTransfer()
                                } else {
                                    Toast.makeText(this@MainActivity, "Please select files first", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        transferProgress?.let { progress ->
                            TransferProgressIndicator(progress)
                        }

                        if (isSuccess) {
                            SuccessAnimation { isSuccess = false }
                        }
                    }
                }
            }
        }

        checkAndRequestPermissions()
    }

    private fun simulateTransfer() {
        // In a real app, this would be tied to airShareService.sendFiles()
        // Here we simulate the progress for UI demonstration
        MainScope().launch {
            transferProgress = 0f
            for (i in 1..100) {
                delay(30)
                transferProgress = i / 100f
            }
            transferProgress = null
            isSuccess = true
            performHapticFeedback()
        }
    }

    private fun performHapticFeedback() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(200)
        }
    }

    private fun checkAndRequestPermissions() {
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun startAirShareService() {
        val intent = Intent(this, AirShareService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun TransferProgressIndicator(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                progress = { progress },
                color = Color(0xFF34C759),
                strokeWidth = 8.dp,
                modifier = Modifier.size(100.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Transferring... ${(progress * 100).toInt()}%",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SuccessAnimation(onComplete: () -> Unit) {
    val scale = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1.2f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
        scale.animateTo(1f)
        delay(2000)
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale.value)
                .background(Color(0xFF34C759), RoundedCornerShape(60.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Success",
                tint = Color.White,
                modifier = Modifier.size(64.dp)
            )
        }
    }
}
