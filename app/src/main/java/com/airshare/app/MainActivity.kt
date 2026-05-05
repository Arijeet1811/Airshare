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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import android.provider.Settings
import android.os.PowerManager
import android.net.ConnectivityManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import com.airshare.app.model.Peer
import com.airshare.app.model.TransferState
import com.airshare.app.service.AirShareService
import com.airshare.app.ui.AirDropOverlay
import com.airshare.app.ui.RadarView
import com.airshare.app.util.LogUtil

class MainActivity : ComponentActivity() {

    private var airShareService: AirShareService? = null
    private var isBound by mutableStateOf(false)

    // UI States
    private var selectedUris by mutableStateOf<List<android.net.Uri>>(emptyList())
    private var manualSelectedPeer by mutableStateOf<Peer?>(null)
    private var isManualSenderMode by mutableStateOf(false)
    
    // Track service state for UI
    private var serviceRunningState by mutableStateOf(false)

    private val permissionsToRequest = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                // Note: READ_MEDIA_IMAGES and READ_MEDIA_VIDEO are also often needed alongside partial access
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            }
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
            LogUtil.i("MainActivity", "All permissions granted, starting service")
            startAirShareService()
            serviceRunningState = true
            Toast.makeText(this, "AirShare is now active", Toast.LENGTH_SHORT).show()
        } else {
            LogUtil.w("MainActivity", "Permissions denied")
            Toast.makeText(this, "Permissions required for AirShare", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AirShareService.LocalBinder
            airShareService = binder.getService()
            isBound = true
            serviceRunningState = AirShareService.isRunning
            LogUtil.d("MainActivity", "Service connected, running=${serviceRunningState}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            airShareService = null
            serviceRunningState = false
            LogUtil.d("MainActivity", "Service disconnected")
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
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    LogUtil.w("MainActivity", "Could not take persistable permission for $uri")
                }
            }
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
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF0F172A),
                                        Color(0xFF1E293B),
                                        Color(0xFF020617)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Branding Header
                        val topPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(top = topPadding + 24.dp, start = 24.dp, end = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "AirShare",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    letterSpacing = (-1.5).sp
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val pulseScale = rememberInfiniteTransition(label = "StatusPulse").animateFloat(
                                        initialValue = 1f,
                                        targetValue = 1.4f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1500, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ), label = "PulseScale"
                                    )
                                    
                                    Box(contentAlignment = Alignment.Center) {
                                        if (serviceRunningState) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp * pulseScale.value)
                                                    .background(
                                                        Color(0xFF34C759).copy(alpha = 0.3f),
                                                        RoundedCornerShape(5.dp)
                                                    )
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    if (serviceRunningState) Color(0xFF34C759) else Color.White.copy(alpha = 0.2f),
                                                    RoundedCornerShape(3.dp)
                                                )
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (serviceRunningState) "Active Node" else "Offline",
                                        fontSize = 12.sp,
                                        color = if (serviceRunningState) Color(0xFF34C759) else Color.White.copy(alpha = 0.3f),
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                            
                            // User Identity Badge
                            Surface(
                                color = Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = com.airshare.app.util.DeviceUtils.getDeviceName(this@MainActivity),
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }

                        RadarView(
                            peers = peers,
                            onPeerTapped = { peer ->
                                manualSelectedPeer = peer
                                isManualSenderMode = true
                            }
                        )

                        // Bottom Controls
                        val bottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = bottomPadding + 32.dp, start = 24.dp, end = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            BatteryDataSaverPrompt()
                            Spacer(modifier = Modifier.height(20.dp))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(32.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.15f),
                                            Color.White.copy(alpha = 0.02f)
                                        )
                                    )
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    ServiceToggleButton(
                                        isRunning = serviceRunningState,
                                        onToggle = { toggleService() }
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    SelectFilesButton()
                                    
                                    if (selectedUris.isNotEmpty()) {
                                        Surface(
                                            modifier = Modifier.padding(top = 12.dp),
                                            color = Color(0xFF34C759).copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(full = true)
                                        ) {
                                            Text(
                                                text = "${selectedUris.size} Artifacts Prepared",
                                                color = Color(0xFF34C759),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
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
                            is TransferState.SecurityVerification -> {
                                SecurityVerificationDialog(
                                    senderFingerprint = state.peerFingerprint,
                                    receiverFingerprint = state.ourFingerprint,
                                    onVerify = { state.response.complete(true) },
                                    onDismiss = { state.response.complete(false) }
                                )
                            }
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

        // ✅ AUTO-START: Check permissions and start service automatically
        checkAndStartService()
    }

    // ✅ NEW: Separate function to check permissions and auto-start
    private fun checkAndStartService() {
        val allGranted = permissionsToRequest.all { 
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            LogUtil.i("MainActivity", "Permissions already granted, auto-starting service")
            startAirShareService()
            serviceRunningState = true
        } else {
            LogUtil.i("MainActivity", "Requesting permissions")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    // ✅ NEW: Toggle service function
    private fun toggleService() {
        if (serviceRunningState) {
            LogUtil.i("MainActivity", "User stopping service")
            stopAirShareService()
            serviceRunningState = false
        } else {
            LogUtil.i("MainActivity", "User starting service")
            startAirShareService()
            serviceRunningState = true
        }
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
    private fun ServiceToggleButton(
        isRunning: Boolean,
        onToggle: () -> Unit
    ) {
        val backgroundColor by animateColorAsState(
            if (isRunning) Color(0xFF34C759) else Color.White.copy(alpha = 0.1f),
            label = "ServiceToggleBg"
        )
        
        Button(
            onClick = onToggle,
            colors = ButtonDefaults.buttonColors(
                containerColor = backgroundColor
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isRunning) "⏹️ Stop Node" else "▶️ Start Node",
                    color = if (isRunning) Color.White else Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }

    @Composable
    private fun SelectFilesButton() {
        OutlinedButton(
            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Text(
                "📁 Select Artifacts", 
                color = Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
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

    @Composable
    private fun BatteryDataSaverPrompt() {
        val context = LocalContext.current
        val isOptimized = remember {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            !pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        
        val isDataRestricted = remember {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            } else false
        }

        if (isOptimized || isDataRestricted) {
            Surface(
                color = Color.Yellow.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isOptimized && isDataRestricted) "⚠️ Throttled: Tap to fix"
                              else if (isOptimized) "🔋 Battery Optimized: Tap to fix"
                              else "📶 Data Restricted: Tap to fix",
                        color = Color.Yellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update service state when returning to app
        serviceRunningState = AirShareService.isRunning
        
        // Agar user ne app kholi hai, toh discovery ko "Fast" mode mein daal do
        if (airShareService?.isLowPowerMode() == true) {
            airShareService?.resetToActiveMode()
            Toast.makeText(this, "Searching nearby...", Toast.LENGTH_SHORT).show()
        }
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
        // 1. Check if all required runtime permissions are granted
        val allGranted = permissionsToRequest.all { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == 
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            LogUtil.w("MainActivity", "Cannot start service: Permissions missing")
            // Optional: Re-trigger permission request if needed
            // checkAndRequestPermissions()
            return
        }

        // 2. Start the service safely based on API level
        val intent = Intent(this, AirShareService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            LogUtil.i("MainActivity", "startAirShareService success")
        } catch (e: Exception) {
            // Handle BackgroundStartRestriction (Android 12+) or other failures
            LogUtil.e("MainActivity", "Failed to start service: ${e.message}")
            Toast.makeText(this, "Service failed to start: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            serviceRunningState = false
        }
    }

    private fun stopAirShareService() {
        val intent = Intent(this, AirShareService::class.java).apply {
            action = AirShareService.ACTION_STOP
        }
        startService(intent)
        LogUtil.i("MainActivity", "stopAirShareService called")
    }
}

@Composable
fun SecurityVerificationDialog(
    senderFingerprint: String,
    receiverFingerprint: String,
    onVerify: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Secure Pairing",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Confirm the pairing code matches the other device to ensure a secure connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    val displayFingerprint = if (senderFingerprint.length >= 8) {
                        "${senderFingerprint.take(4)} ${senderFingerprint.drop(4)}"
                    } else senderFingerprint

                    Text(
                        text = displayFingerprint,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        color = Color.White
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onVerify,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Verify & Connect", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reject Connection", color = Color.White.copy(alpha = 0.4f))
            }
        },
        containerColor = Color(0xFF0F172A),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(32.dp)
    )
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
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { 1f },
                    color = Color.White.copy(alpha = 0.1f),
                    strokeWidth = 6.dp,
                    modifier = Modifier.size(160.dp)
                )
                CircularProgressIndicator(
                    progress = { progress },
                    color = Color(0xFF34C759),
                    strokeWidth = 6.dp,
                    modifier = Modifier.size(160.dp)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Complete",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Text(
                text = fileName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "Transferring content...",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
            ) {
                Text("Cancel Transfer", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun SuccessAnimation(onStart: () -> Unit = {}, onComplete: () -> Unit) {
    val scale = remember { Animatable(0f) }
    val ringsAlpha = remember { Animatable(0.6f) }
    val ringsScale = remember { Animatable(1f) }
    
    LaunchedEffect(Unit) {
        onStart()
        launch {
            scale.animateTo(
                targetValue = 1.2f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
            scale.animateTo(1f)
        }
        launch {
            ringsScale.animateTo(
                targetValue = 2.5f,
                animationSpec = tween(1500, easing = EaseOutExpo)
            )
        }
        launch {
            ringsAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(1500, easing = LinearEasing)
            )
        }
        delay(2500)
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        // Expanding success rings
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(ringsScale.value)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF34C759).copy(alpha = ringsAlpha.value), Color.Transparent)
                    ),
                    shape = RoundedCornerShape(60.dp)
                )
        )
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Transfer Complete",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.scale(scale.value)
            )
            Text(
                text = "Artifacts successfully deployed",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
