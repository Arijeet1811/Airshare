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
                var connected = false
                var currentDelay = 1500L // Incremental delay
                
                repeat(6) { attempt ->
                    if (connected) return@repeat
                    
                    kotlinx.coroutines.delay(currentDelay)
                    // Increase delay for next attempt
                    currentDelay = (currentDelay * 1.5).toLong().coerceAtMost(5000L)

                    val wifiDevices = wifiDirectManager.discoveredWifiDevices.value
                    
                    // Strategy 1: Name match
                    var matchedDevice = wifiDevices.find {
                        it.deviceName.trim().equals(peer.name.trim(), ignoreCase = true)
                    }
                    
                    // Strategy 2: Use BLE Identifier if it looks like a MAC (some devices)
                    if (matchedDevice == null && peer.bleIdentifier.isNotEmpty()) {
                        matchedDevice = wifiDevices.find {
                            it.deviceAddress.replace(":", "").equals(peer.bleIdentifier.replace(":", ""), ignoreCase = true)
                        }
                    }

                    // Strategy 3: Fallback to first available device if solitary
                    if (matchedDevice == null && wifiDevices.size == 1) {
                        matchedDevice = wifiDevices.first()
                        android.util.Log.d("AirShareService", 
                            "Solitary device fallback: using ${matchedDevice.deviceName}")
                    }

                    if (matchedDevice != null) {
                        android.util.Log.i("AirShareService", 
                            "Connecting to matched device: ${matchedDevice.deviceName} (Attempt ${attempt + 1})")
                        wifiDirectManager.connect(matchedDevice)
                        connected = true
                    } else {
                        android.util.Log.w("AirShareService", 
                            "Attempt ${attempt + 1}: no match for ${peer.name}. Devices: ${wifiDevices.map { it.deviceName }}")
                        wifiDirectManager.initiateDiscovery()
                    }
                }
                
                if (!connected) {
                    android.util.Log.e("AirShareService", "Failed to connect to ${peer.name} after multiple attempts")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        _transferState.value = TransferState.Idle
                    }
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

        // Initial notification
        updateNotification("AirShare is active - Looking for nearby devices...")
        
        // Make sure BLE starts properly
        bleManager.startDiscovery()
    }

    fun setPendingFiles(files: List<Pair<Uri, String>>) {
        pendingFiles = files
    }

    fun trySendNowIfConnected() {
        wifiDirectManager.requestConnectionInfo()
    }

    fun cancelTransfer() {
        _transferState.value = TransferState.Idle
        // Cancel all running coroutines except the main serviceJob
        // by cancelling the current transfer coroutine
        // For now reset state — socket will timeout on its own
    }

    private var receivingJob: Job? = null
    
    fun startReceiving() {
        receivingJob?.cancel() // Cancel previous if any
        
        receivingJob = serviceScope.launch(Dispatchers.IO) {
            transferManager.startReceiving(
                contentResolver = contentResolver,
                scope = serviceScope,
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
                        kotlinx.coroutines.delay(2500)
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
        if (intent?.action == ACTION_RESTART_BLE) {
            bleManager.stopDiscovery()
            serviceScope.launch {
                kotlinx.coroutines.delay(800)
                bleManager.startDiscovery()
            }
            return START_STICKY
        }
        isRunning = true
        startForegroundService()
        bleManager.startDiscovery()
        startReceiving()
        schedulePeriodicBleRestart()
        return START_STICKY
    }

    fun getDiscoveredPeers(): StateFlow<List<Peer>> = bleManager.discoveredPeers

    override fun onDestroy() {
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
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.airshare.app.service.STOP"
        const val ACTION_RESTART_BLE = "com.airshare.app.service.RESTART_BLE"
        
        @Volatile
        var isRunning = false
            private set
    }
}
