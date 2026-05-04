package com.airshare.app
import com.airshare.app.model.TransferState

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            // Service will be started by user button
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

            val transferState by if (isBound) {
                airShareService?.transferState?.collectAsState() ?: mutableStateOf(TransferState.Idle)
            } else {
                remember { mutableStateOf(TransferState.Idle) }
            }

            val triggeredPeer = peers.find { it.isProximityTriggered }
            var manualPeer by remember { mutableStateOf<Peer?>(null) }
            var isSenderMode by remember { mutableStateOf(false) }
            var dismissedPeerId by remember { mutableStateOf<String?>(null) }

            // Handle incoming request UI
            val incomingRequest = transferState as? TransferState.Request
            val activeOverlayPeer = incomingRequest?.let { 
                Peer(id = "incoming", name = "Sender", rssi = 0) // We don't know the peer precisely here without more handshake, but we show the overlay
            } ?: manualPeer ?: if (triggeredPeer?.id != dismissedPeerId) triggeredPeer else null

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        RadarView(
                            peers = peers,
                            onPeerTapped = { peer ->
                                manualPeer = peer
                                isSenderMode = true
                            }
                        )

                        // File Selection Button
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            var serviceRunning by remember { mutableStateOf(AirShareService.isRunning) }
                            
                            // Background Service Toggle
                            Button(
                                onClick = {
                                    if (AirShareService.isRunning) {
                                        stopAirShareService()
                                    } else {
                                        startAirShareService()
                                    }
                                    serviceRunning = !serviceRunning
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (serviceRunning) Color(0xFF34C759) else Color(0xFF007AFF)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Text(if (serviceRunning) "Service: ON" else "Start Background Service", color = Color.White)
                            }

                            Button(
                                onClick = { filePickerLauncher.launch("*/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Select Files to Share", color = Color.White)
                            }
                        }

                        AirDropOverlay(
                            peer = activeOverlayPeer,
                            isSender = incomingRequest == null && isSenderMode,
                            onDismiss = { 
                                if (incomingRequest != null) {
                                    incomingRequest.response.complete(false)
                                } else {
                                    dismissedPeerId = activeOverlayPeer?.id 
                                    manualPeer = null
                                    isSenderMode = false
                                }
                            },
                            onAccept = { peer ->
                                if (incomingRequest != null) {
                                    incomingRequest.response.complete(true)
                                } else {
                                    if (selectedUris.isNotEmpty()) {
                                        if (isBound && airShareService != null) {
                                            val filesToSend = selectedUris.map { uri ->
                                                Pair(uri, getFileName(uri))
                                            }
                                            airShareService?.setPendingFiles(filesToSend)
                                            isSenderMode = true 
                                        } else {
                                            Toast.makeText(this@MainActivity, "Service not bound", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(this@MainActivity, "Please select files first", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )

                        when (val state = transferState) {
                            is TransferState.Transferring -> {
                                TransferProgressIndicator(state.progress)
                            }
                            is TransferState.Success -> {
                                var showSuccess by remember { mutableStateOf(true) }
                                if (showSuccess) {
                                    SuccessAnimation { 
                                        showSuccess = false
                                        // Reset dismissed peer id to allow new triggers
                                        dismissedPeerId = null
                                        manualPeer = null
                                        isSenderMode = false
                                    }
                                }
                            }
                            is TransferState.Error -> {
                                Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun getFileName(uri: android.net.Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
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

    private fun stopAirShareService() {
        val intent = Intent(this, AirShareService::class.java).apply {
            action = AirShareService.ACTION_STOP
        }
        startService(intent)
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
