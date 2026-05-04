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
import com.airshare.app.p2p.FileTransferManager
import com.airshare.app.p2p.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class AirShareService : Service() {

    private val binder = LocalBinder()
    private lateinit var bleManager: BleManager
    private lateinit var wifiDirectManager: WifiDirectManager
    private val transferManager = FileTransferManager()
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    inner class LocalBinder : Binder() {
        fun getService(): AirShareService = this@AirShareService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bleManager = BleManager(this)
        wifiDirectManager = WifiDirectManager(this) { info ->
            // In a real flow, this handles the host IP for socket connection
            if (!info.isGroupOwner) {
                // We are the client, wait for host to be ready then send?
            }
        }
    }

    fun sendFiles(host: String, files: List<File>) {
        serviceScope.launch {
            transferManager.sendFiles(host, files) { index, sent, total ->
                updateNotification("Sending file ${index + 1}: ${(sent * 100 / total)}%")
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
        return START_STICKY
    }

    fun getDiscoveredPeers(): StateFlow<List<Peer>> = bleManager.discoveredPeers

    override fun onDestroy() {
        isRunning = false
        bleManager.stopDiscovery()
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notification = createNotification("Searching for nearby devices...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Specify foreground service type for Android 10+
            // We use CONNECTED_DEVICE as required for Bluetooth/Wi-Fi P2P communication
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // For Android 14+, we can also include NEARBY_DEVICE if we wanted, but let's stick to the requested change.
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
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
