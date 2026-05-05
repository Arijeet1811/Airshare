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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airshare.app.model.Peer
import com.airshare.app.model.TransferState
import com.airshare.app.service.AirShareService
import com.airshare.app.ui.AirDropOverlay
import com.airshare.app.ui.RadarView

class MainActivity : ComponentActivity() {

    private var airShareService: AirShareService? = null
    private var isBound by mutableStateOf(false)

    // UI States
    private var selectedUris by mutableStateOf<List<android.net.Uri>>(emptyList())
    private var manualSelectedPeer by mutableStateOf<Peer?>(null)
    private var isManualSenderMode by mutableStateOf(false)

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
            startAirShareService()
            Toast.makeText(this, "AirShare is now active", Toast.LENGTH_SHORT).show()
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
            val peers by if (isBound && airShareService != null) {
                airShareService!!.getDiscoveredPeers().collectAsState()
            } else remember { mutableStateOf(emptyList<Peer>()) }

            val transferState by if (isBound && airShareService != null) {
                airShareService!!.transferState.collectAsState()
            } else remember { mutableStateOf(TransferState.Idle) }

            val incomingRequest = transferState as? TransferState.Request
            val triggeredPeer = peers.find { it.isProximityTriggered }

            // Clean logic for showing overlay
            val showOverlayPeer = when {
                incomingRequest != null -> Peer(id = "incoming", name = "Nearby Device", rssi = 0)
                manualSelectedPeer != null -> manualSelectedPeer
                triggeredPeer != null -> triggeredPeer
                else -> null
            }

            val isSenderMode = incomingRequest == null && (manualSelectedPeer != null || isManualSenderMode)

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
                                manualSelectedPeer = peer
                                isManualSenderMode = true
                            }
                        )

                        // Bottom Controls
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ServiceToggleButton()
                            SelectFilesButton()
                        }

                        // Overlay
                        AirDropOverlay(
                            peer = showOverlayPeer,
                            isSender = isSenderMode,
                            fileCount = incomingRequest?.fileCount ?: 1,
                            totalSizeBytes = incomingRequest?.fileSize ?: 0L,
                            onDismiss = {
                                if (incomingRequest != null) {
                                    incomingRequest.response.complete(false)
                                } else {
                                    // Manual sender mode dismiss
                                    manualSelectedPeer = null
                                    isManualSenderMode = false
                                }
                            },
                            onAccept = { peer ->
                                if (incomingRequest != null) {
                                    incomingRequest.response.complete(true)
                                } else {
                                    handleSendFiles()
                                    // Reset manual mode after starting send
                                    manualSelectedPeer = null
                                    isManualSenderMode = false
                                }
                            }
                        )

                        // Transfer Status Overlays
                        when (val state = transferState) {
                            is TransferState.Transferring -> {
                                TransferProgressIndicator(
                                    progress = state.progress,
                                    fileName = state.fileName,
                                    onCancel = { airShareService?.cancelTransfer() }
                                )
                            }
                            is TransferState.Success -> SuccessAnimationWithReset()
                            is TransferState.Error -> {
                                LaunchedEffect(state) {
                                    Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        checkAndRequestPermissions()
    }

    private fun handleSendFiles() {
        if (selectedUris.isNotEmpty() && isBound && airShareService != null) {
            val filesToSend = selectedUris.map { uri ->
                Pair(uri, getFileName(uri))
            }
            airShareService?.setPendingFiles(filesToSend)
            airShareService?.trySendNowIfConnected()
        } else {
            Toast.makeText(this, "Please select files first", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun ServiceToggleButton() {
        var serviceRunning by remember { mutableStateOf(AirShareService.isRunning) }
        Button(
            onClick = {
                if (AirShareService.isRunning) stopAirShareService() else startAirShareService()
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
    }

    @Composable
    private fun SelectFilesButton() {
        Button(
            onClick = { filePickerLauncher.launch("*/*") },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Select Files to Share", color = Color.White)
        }
    }

    @Composable
    private fun SuccessAnimationWithReset() {
        var showSuccess by remember { mutableStateOf(true) }
        if (showSuccess) {
            SuccessAnimation(
                onStart = { performHapticFeedback() },
                onComplete = {
                    showSuccess = false
                    manualSelectedPeer = null
                    isManualSenderMode = false
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
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
        val allGranted = permissionsToRequest.all { 
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startAirShareService()
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
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
fun TransferProgressIndicator(
    progress: Float,
    fileName: String = "",
    onCancel: () -> Unit = {}
) {
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
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = fileName,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.Red.copy(alpha = 0.8f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun SuccessAnimation(onStart: () -> Unit = {}, onComplete: () -> Unit) {
    val scale = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        onStart()
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
