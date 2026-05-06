package com.airshare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.airshare.app.MainActivity
import com.airshare.app.R
import com.airshare.app.util.LogUtil

import com.airshare.app.ble.BleManager
import com.airshare.app.model.Peer
import com.airshare.app.model.TransferState
import com.airshare.app.p2p.FileTransferManager
import com.airshare.app.p2p.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.net.Uri
import java.io.File

import kotlinx.coroutines.CompletableDeferred
import android.content.pm.ServiceInfo

class AirShareService : Service() {

    private val binder = LocalBinder()
    private lateinit var bleManager: BleManager
    private lateinit var wifiDirectManager: WifiDirectManager
    private val transferManager = FileTransferManager()
    
    private var pendingFiles: List<Pair<Uri, String>> = emptyList()
    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private val _incomingPeer = MutableStateFlow<Peer?>(null)
    val incomingPeer: StateFlow<Peer?> = _incomingPeer.asStateFlow()

    private var lastConnectedHost: String? = null

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    inner class LocalBinder : Binder() {
        fun getService(): AirShareService = this@AirShareService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private var pendingConnectionRequest: Peer? = null
    private val connectionAttempts = mutableMapOf<String, MutableList<Long>>()
    private val MAX_ATTEMPTS_PER_MINUTE = 3

    private fun isRateLimited(peerId: String): Boolean {
        val now = System.currentTimeMillis()
        val attempts = connectionAttempts.getOrPut(peerId) { mutableListOf() }
        attempts.removeAll { now - it > 60_000 }
        
        if (attempts.size >= MAX_ATTEMPTS_PER_MINUTE) {
            LogUtil.w("AirShareService", "Rate limited for peer: $peerId")
            return true
        }
        attempts.add(now)
        return false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wifiDirectManager = WifiDirectManager(this) { info ->
            if (info.groupFormed && !info.isGroupOwner) {
                val targetHost = info.groupOwnerAddress?.hostAddress ?: return@WifiDirectManager
                if (pendingFiles.isNotEmpty()) {
                    val filesToSend = pendingFiles.toList()
                    pendingFiles = emptyList()
                    sendFiles(targetHost, filesToSend)
                } else {
                    // Store host so we can send when files are selected later
                    lastConnectedHost = targetHost
                }
            }
        }
        wifiDirectManager.registerReceiver(this)

        bleManager = BleManager(this) { peer ->
            pendingConnectionRequest = peer
            _incomingPeer.value = peer   // Show in-app overlay
            showConnectionNotification(peer)  // Also show notification
        }

        // Auto-update notification based on state
        serviceScope.launch {
            _transferState.collect { state ->
                when (state) {
                    is TransferState.Idle -> updateNotification("Searching for nearby devices...")
                    is TransferState.Transferring -> {
                        val percent = (state.progress * 100).toInt()
                        updateNotification("${state.fileName}: $percent%")
                    }
                    is TransferState.Success -> updateNotification("Transfer successful!")
                    is TransferState.Error -> updateNotification("Error: ${state.message}")
                    is TransferState.Request -> updateNotification("Incoming request: ${state.fileName}")
                    is TransferState.SecurityVerification -> updateNotification("Verifying security code...")
                }
            }
        }

        // Initial notification
        updateNotification("AirShare is active - Looking for nearby devices...")
    }

    fun setPendingFiles(files: List<Pair<Uri, String>>) {
        pendingFiles = files
        trySendNowIfConnected()
    }

    fun trySendNowIfConnected() {
        val host = lastConnectedHost
        if (host != null && pendingFiles.isNotEmpty()) {
            val filesToSend = pendingFiles.toList()
            pendingFiles = emptyList()
            lastConnectedHost = null
            sendFiles(host, filesToSend)
        } else {
            wifiDirectManager.requestConnectionInfo()
        }
    }

    fun clearIncomingPeer() {
        _incomingPeer.value = null
        pendingConnectionRequest = null
        bleManager.setConnectionRequestMode(false)
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(CONNECTION_NOTIFICATION_ID)
    }

    fun acceptIncomingPeer() {
        val peer = pendingConnectionRequest ?: return
        serviceScope.launch(Dispatchers.IO) {
            wifiDirectManager.initiateDiscovery()
            delay(2000)
            val wifiDevices = wifiDirectManager.discoveredWifiDevices.value
            val matchedDevice = wifiDevices.find {
                it.deviceName.trim().equals(peer.name.trim(), ignoreCase = true)
            } ?: wifiDevices.find { dev ->
                peer.bleIdentifier.isNotEmpty() && dev.deviceAddress.replace(":", "").equals(peer.bleIdentifier.replace(":", ""), ignoreCase = true)
            } ?: if (wifiDevices.size == 1) wifiDevices.first() else null
            
            matchedDevice?.let {
                wifiDirectManager.connect(it)
            } ?: LogUtil.w("AirShareService", "No WiFi device found for peer: ${peer.name}")
        }
    }

    fun connectToPeer(peer: Peer) {
        serviceScope.launch(Dispatchers.IO) {
            lastConnectedHost = null
            wifiDirectManager.initiateDiscovery()
            delay(2000)  // wait for devices

            val matchedDevice = wifiDirectManager.discoveredWifiDevices.value.find { dev ->
                dev.deviceName.trim().equals(peer.name.trim(), ignoreCase = true) ||
                (peer.bleIdentifier.isNotEmpty() && dev.deviceAddress.replace(":", "")
                    .equals(peer.bleIdentifier.replace(":", ""), ignoreCase = true))
            }
            matchedDevice?.let {
                LogUtil.i("AirShareService", "Initiating WiFi Direct to ${it.deviceName}")
                wifiDirectManager.connect(it)
            } ?: run {
                LogUtil.w("AirShareService", "No WiFi device matched for peer ${peer.name}")
                withContext(Dispatchers.Main) {
                    _transferState.value = TransferState.Error("Could not find peer on WiFi")
                }
            }
        }
    }

    fun cancelTransfer() {
        _transferState.value = TransferState.Idle
        transferManager.stopReceiving()   // Close the ServerSocket immediately
        serviceScope.launch {
            delay(500)  // Wait for old socket to fully close
            startReceiving()                  // Reopen a fresh ServerSocket for next user
        }
    }

    private var receivingJob: Job? = null
    
    fun startReceiving() {
        receivingJob?.cancel() // Cancel previous if any
        
        receivingJob = serviceScope.launch(Dispatchers.IO) {
            transferManager.startReceiving(
                contentResolver = contentResolver,
                scope = serviceScope,
                onVerifyFingerprint = { senderFingerprint, ourFingerprint ->
                    val deferred = CompletableDeferred<Boolean>()
                    _transferState.value = TransferState.SecurityVerification(
                        ourFingerprint = ourFingerprint,
                        peerFingerprint = senderFingerprint,
                        response = deferred
                    )
                    deferred.await()
                },
                onReceiveRequest = { fileName, fileSize, _, fileCount ->
                    val deferred = CompletableDeferred<Boolean>()
                    _transferState.value = TransferState.Request(
                        fileName = fileName,
                        fileSize = fileSize,
                        fileCount = fileCount,
                        response = deferred
                    )
                    val accepted = deferred.await()
                    if (!accepted) {
                        _transferState.value = TransferState.Idle
                    }
                    accepted
                },
                onProgress = { fileName, progress, total ->
                    val percentage = if (total > 0) progress.toFloat() / total else 0f
                    _transferState.value = TransferState.Transferring(percentage, fileName)
                },
                onTransferComplete = {
                    _transferState.value = TransferState.Success
                    // Auto reset after success
                    serviceScope.launch {
                delay(2500)
                        _transferState.compareAndSet(TransferState.Success, TransferState.Idle)
                    }
                }
            )
        }
    }

    fun stopReceiving() {
        transferManager.stopReceiving()
    }

    fun sendFiles(host: String, files: List<Pair<Uri, String>>) {
        serviceScope.launch {
            _transferState.value = TransferState.Transferring(0f, "Starting...")
            
            var attempt = 0
            val maxAttempts = 3
            var success = false
            
            while (attempt < maxAttempts && !success) {
                attempt++
                val result = transferManager.sendFiles(
                    host, 
                    contentResolver, 
                    files,
                    onVerifyFingerprint = { ourFingerprint, receiverFingerprint ->
                        val deferred = CompletableDeferred<Boolean>()
                        _transferState.value = TransferState.SecurityVerification(
                            ourFingerprint = ourFingerprint,
                            peerFingerprint = receiverFingerprint,
                            response = deferred
                        )
                        deferred.await()
                    },
                    onProgress = { index, sent, total ->
                        val progress = sent.toFloat() / (if(total == 0L) 1L else total)
                        if (index < files.size) {
                            _transferState.value = TransferState.Transferring(progress, files[index].second)
                        }
                    }
                )
                
                result.onSuccess {
                    _transferState.value = TransferState.Success
                    success = true
                }.onFailure { e ->
                    LogUtil.e("AirShareService", "Send attempt $attempt failed", e)
                    if (attempt == maxAttempts) {
                        _transferState.value = TransferState.Error(e.message ?: "Send failed")
                    } else {
                        delay(2000)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART_BLE) {
            bleManager.stopDiscovery()
            serviceScope.launch {
                delay(800)
                bleManager.startDiscovery()
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_ACCEPT_CONNECTION) {
            val peerName = intent.getStringExtra("peer_name") ?: return START_STICKY
            val peerId = intent.getStringExtra("peer_id") ?: return START_STICKY
            
            if (isRateLimited(peerId)) return START_STICKY

            val peer = pendingConnectionRequest ?: Peer(peerId, peerName, 0)
            
            // Now connect securely since user confirmed
            serviceScope.launch(Dispatchers.IO) {
                wifiDirectManager.initiateDiscovery()
                delay(2000)
                val matchedDevice = wifiDirectManager.discoveredWifiDevices.value.find {
                    it.deviceName.trim().equals(peer.name.trim(), ignoreCase = true)
                } ?: wifiDirectManager.discoveredWifiDevices.value.find { dev ->
                    peer.bleIdentifier.isNotEmpty() && dev.deviceAddress.replace(":", "").equals(peer.bleIdentifier.replace(":", ""), ignoreCase = true)
                }
                
                matchedDevice?.let { 
                    LogUtil.i("AirShareService", "User accepted connection to ${it.deviceName}")
                    wifiDirectManager.connect(it) 
                } ?: run {
                    LogUtil.w("AirShareService", "Could not find wifi device for confirmed peer ${peer.name}")
                }
            }
            pendingConnectionRequest = null
            _incomingPeer.value = null
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(CONNECTION_NOTIFICATION_ID)
            return START_STICKY
        }
        if (intent?.action == ACTION_DECLINE_CONNECTION) {
            pendingConnectionRequest = null
            _incomingPeer.value = null
            bleManager.setConnectionRequestMode(false)
            wifiDirectManager.cleanup() // Cancel any ongoing WiFi connection
            _transferState.value = TransferState.Idle
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(CONNECTION_NOTIFICATION_ID)
            return START_STICKY
        }
        isRunning = true
        startForegroundService()
        bleManager.startDiscovery()
        startReceiving()
        schedulePeriodicBleRestart()
        wifiDirectManager.initiateDiscovery()
        return START_STICKY
    }

    private fun showConnectionNotification(peer: Peer) {
        // Intent to open MainActivity (shows the in-app overlay)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("peer_name", peer.name)
            putExtra("peer_id", peer.id)
            putExtra("show_overlay", true)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(this, AirShareService::class.java).apply {
            action = ACTION_ACCEPT_CONNECTION
            putExtra("peer_name", peer.name)
            putExtra("peer_id", peer.id)
        }
        val acceptPendingIntent = PendingIntent.getService(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, AirShareService::class.java).apply {
            action = ACTION_DECLINE_CONNECTION
            putExtra("peer_id", peer.id)
        }
        val declinePendingIntent = PendingIntent.getService(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use HIGH_PRIORITY channel for heads-up notification
        val notification = NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setContentTitle("${peer.name} wants to connect")
            .setContentText("Tap to open AirShare and accept or decline")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openAppPendingIntent)
            .setFullScreenIntent(openAppPendingIntent, true)  // Show as heads-up / full-screen
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
            .addAction(android.R.drawable.ic_menu_save, "Open & Accept", acceptPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(CONNECTION_NOTIFICATION_ID, notification)
    }

    fun getDiscoveredPeers(): StateFlow<List<Peer>> = bleManager.discoveredPeers

    fun getBleManager(): BleManager = bleManager

    fun isLowPowerMode(): Boolean = bleManager.isLowPowerMode()

    fun resetToActiveMode() {
        bleManager.resetToActiveMode()
    }

    override fun onDestroy() {
        stopForeground(true)
        isRunning = false
        cancelPeriodicBleRestart()
        bleManager.stopDiscovery()
        wifiDirectManager.unregisterReceiver(this)
        wifiDirectManager.cleanup()
        stopReceiving()
        pendingFiles = emptyList()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notification = createNotification("Searching for nearby devices...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                LogUtil.e("AirShareService", "Failed to start foreground service with types", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }

        val stopIntent = Intent(this, AirShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirShare")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)

        // Android 13+ (API 33+) ensures notification is immediate for FGS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "AirShare Service Channel",
                NotificationManager.IMPORTANCE_HIGH // High importance for visibility
            )
            serviceChannel.description = "Required for AirShare background features"
            manager?.createNotificationChannel(serviceChannel)

            val connectionChannel = NotificationChannel(
                CONNECTION_CHANNEL_ID,
                "AirShare Connection Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows when a nearby device wants to connect"
                enableVibration(true)
                setShowBadge(true)
            }
            manager?.createNotificationChannel(connectionChannel)
        }
    }

    fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun schedulePeriodicBleRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, AirShareService::class.java).apply {
            action = ACTION_RESTART_BLE
        }
        val pendingIntent = android.app.PendingIntent.getService(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setRepeating(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 10 * 60 * 1000,
            10 * 60 * 1000,
            pendingIntent
        )
    }

    private fun cancelPeriodicBleRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, AirShareService::class.java).apply {
            action = ACTION_RESTART_BLE
        }
        val pendingIntent = android.app.PendingIntent.getService(
            this, 0, intent,
            android.app.PendingIntent.FLAG_NO_CREATE or
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    companion object {
        private const val CHANNEL_ID = "AirShareServiceChannel"
        private const val CONNECTION_CHANNEL_ID = "AirShareConnectionChannel"
        private const val NOTIFICATION_ID = 1
        private const val CONNECTION_NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.airshare.app.service.STOP"
        const val ACTION_RESTART_BLE = "com.airshare.app.service.RESTART_BLE"
        const val ACTION_ACCEPT_CONNECTION = "com.airshare.app.service.ACCEPT_CONNECTION"
        const val ACTION_DECLINE_CONNECTION = "com.airshare.app.service.DECLINE_CONNECTION"
        
        @Volatile
        var isRunning = false
            private set
    }
}
