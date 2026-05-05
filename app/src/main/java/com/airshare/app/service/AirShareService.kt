package com.airshare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.airshare.app.MainActivity
import com.airshare.app.R

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

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    inner class LocalBinder : Binder() {
        fun getService(): AirShareService = this@AirShareService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wifiDirectManager = WifiDirectManager(this) { info ->
            if (info.groupFormed && !info.isGroupOwner) {
                val targetHost = info.groupOwnerAddress.hostAddress
                if (pendingFiles.isNotEmpty()) {
                    sendFiles(targetHost, pendingFiles)
                    pendingFiles = emptyList() // Clear after sending
                }
            }
        }
        wifiDirectManager.registerReceiver(this)

        bleManager = BleManager(this) { peer ->
            wifiDirectManager.initiateDiscovery()
            serviceScope.launch(Dispatchers.IO) {
                // Retry loop: try to match and connect up to 5 times with 2s gap
                var connected = false
                repeat(5) { attempt ->
                    if (connected) return@repeat
                    kotlinx.coroutines.delay(2000)
                    val wifiDevices = wifiDirectManager.discoveredWifiDevices.value
                    val matchedDevice = wifiDevices.find { 
                        it.deviceName.trim().equals(peer.name.trim(), ignoreCase = true) 
                    }
                    if (matchedDevice != null) {
                        android.util.Log.d("AirShareService", 
                            "Matched on attempt ${attempt + 1}: ${peer.name}")
                        wifiDirectManager.connect(matchedDevice)
                        connected = true
                    } else {
                        android.util.Log.w("AirShareService", 
                            "Attempt ${attempt + 1}: no match for ${peer.name}, retrying...")
                        // Re-trigger discovery on each retry
                        wifiDirectManager.initiateDiscovery()
                    }
                }
                if (!connected) {
                    android.util.Log.e("AirShareService", 
                        "Failed to match WiFi Direct device for BLE peer: ${peer.name}")
                }
            }
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
                }
            }
        }
    }

    fun setPendingFiles(files: List<Pair<Uri, String>>) {
        pendingFiles = files
    }

    fun startReceiving() {
        serviceScope.launch {
            while (isRunning) {
                transferManager.receiveFiles(
                    contentResolver = contentResolver,
                    onReceiveRequest = { fileName, fileSize ->
                        val deferred = CompletableDeferred<Boolean>()
                        _transferState.value = TransferState.Request(fileName, fileSize, deferred)
                        val result = deferred.await()
                        if (!result) {
                            _transferState.value = TransferState.Idle
                        }
                        result
                    },
                    onProgress = { fileName, progress, total ->
                        _transferState.value = TransferState.Transferring(progress.toFloat() / total, fileName)
                    }
                ).onSuccess {
                    _transferState.value = TransferState.Success
                }.onFailure { e ->
                    _transferState.value = TransferState.Error(e.message ?: "Receive failed")
                }
            }
        }
    }

    fun sendFiles(host: String, files: List<Pair<Uri, String>>) {
        serviceScope.launch {
            _transferState.value = TransferState.Transferring(0f, "Starting...")
            
            var attempt = 0
            val maxAttempts = 3
            var success = false
            
            while (attempt < maxAttempts && !success) {
                attempt++
                val result = transferManager.sendFiles(host, contentResolver, files) { index, sent, total ->
                    val progress = sent.toFloat() / (if(total == 0L) 1L else total)
                    _transferState.value = TransferState.Transferring(progress, files[index].second)
                }
                
                result.onSuccess {
                    _transferState.value = TransferState.Success
                    success = true
                }.onFailure { e ->
                    if (attempt == maxAttempts) {
                        _transferState.value = TransferState.Error(e.message ?: "Send failed")
                    } else {
                        kotlinx.coroutines.delay(2000)
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
        isRunning = true
        startForegroundService()
        bleManager.startDiscovery()
        startReceiving()
        return START_STICKY
    }

    fun getDiscoveredPeers(): StateFlow<List<Peer>> = bleManager.discoveredPeers

    override fun onDestroy() {
        isRunning = false
        bleManager.stopDiscovery()
        wifiDirectManager.unregisterReceiver(this)
        wifiDirectManager.cleanup()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notification = createNotification("Searching for nearby devices...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, type)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirShare")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Use LOW for normal active service to be less intrusive
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "AirShare Service Channel",
                NotificationManager.IMPORTANCE_HIGH // High importance for visibility
            )
            serviceChannel.description = "Required for AirShare background features"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(content))
    }

    companion object {
        private const val CHANNEL_ID = "AirShareServiceChannel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.airshare.app.service.STOP"
        
        @Volatile
        var isRunning = false
            private set
    }
}
