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
        bleManager = BleManager(this) { peer ->
            wifiDirectManager.initiateDiscovery()
        }
        wifiDirectManager = WifiDirectManager(this) { info ->
            if (info.groupFormed && !info.isGroupOwner) {
                val targetHost = info.groupOwnerAddress.hostAddress
                if (pendingFiles.isNotEmpty()) {
                    sendFiles(targetHost, pendingFiles)
                    pendingFiles = emptyList() // Clear after sending
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
                        updateNotification("Receiving $fileName: ${(progress * 100 / (if(total == 0L) 1L else total))}%")
                    }
                ).onSuccess {
                    _transferState.value = TransferState.Success
                    updateNotification("Received completed")
                }.onFailure { e ->
                    _transferState.value = TransferState.Error(e.message ?: "Receive failed")
                    updateNotification("Receive error")
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
                    updateNotification("Sending file ${index + 1}: ${(progress * 100).toInt()}%")
                }
                
                result.onSuccess {
                    _transferState.value = TransferState.Success
                    updateNotification("Files sent successfully!")
                    success = true
                }.onFailure { e ->
                    if (attempt == maxAttempts) {
                        _transferState.value = TransferState.Error(e.message ?: "Send failed")
                        updateNotification("Failed to send files")
                    } else {
                        updateNotification("Retrying send (Attempt $attempt)...")
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirShare Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download) // Use a system icon for now
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX) // High priority for better visibility
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
