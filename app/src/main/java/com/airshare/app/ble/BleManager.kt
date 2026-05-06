package com.airshare.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.airshare.app.util.LogUtil
import com.airshare.app.model.Peer
import com.airshare.app.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onProximityDetected: (Peer) -> Unit
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    // ✅ FAST DISCOVERY: Unique UUID for AirShare
    private val SERVICE_UUID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private val CONNECTION_REQUEST_UUID = UUID.fromString("a1b2c3d4-e89b-12d3-a456-426614174000")
    private val PROXIMITY_THRESHOLD = -65  // Made stricter for faster trigger

    companion object {
        private const val AIRSHARE_MANUFACTURER_ID = 0x07CD
        // ✅ OPTIMIZED: Much faster scanning
        private const val SCAN_DURATION_MS = 3000L      // 3 seconds (was 10s)
        private const val SCAN_INTERVAL_MS = 1000L      // 1 second gap (was 5s)
        private const val MAX_ADVERTISING_RETRIES = 3
    }

    private val sessionId: String by lazy {
        val prefs = context.getSharedPreferences("airshare_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().take(12)
            prefs.edit().putString("device_id", id).apply()
        }
        id
    }

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var discoveryJob: Job? = null
    private var isDiscoveryActive = false
    private var isScanning = false
    private var isAdvertising = false
    private var isConnectionRequesting = false
    private var advertisingRetryCount = 0
    
    // ✅ NEW: For instant proximity detection
    private val recentlyTriggeredPeers = mutableSetOf<String>()
    private val TRIGGER_COOLDOWN_MS = 3000L  // Don't re-trigger same peer within 3 seconds

    // RSSI Smoothing: Map of Peer ID to list of last N RSSI values
    private val rssiHistory = mutableMapOf<String, MutableList<Int>>()
    private val RSSI_WINDOW_SIZE = 5

    private fun getSmoothedRssi(peerId: String, currentRssi: Int): Int {
        val history = rssiHistory.getOrPut(peerId) { mutableListOf() }
        history.add(currentRssi)
        if (history.size > RSSI_WINDOW_SIZE) {
            history.removeAt(0)
        }
        return history.average().toInt()
    }

    private var isLowPowerMode = false
    private val ACTIVE_SCAN_DURATION = 5 * 60 * 1000L // 5 Minutes
    private val DUTY_CYCLE_INTERVAL = 30 * 1000L // 30 Seconds gap
    private val DUTY_CYCLE_SCAN_TIME = 5000L // 5 Seconds scan

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (isLowPowerMode) {
                LogUtil.i("BleManager", "Peer found! Switching to Active Mode.")
                isLowPowerMode = false // Wake up
            }

            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.name ?: device.address ?: "Unknown"
            val rssi = result.rssi
            
            LogUtil.d("BleManager", "📡 Found: $deviceName, RSSI=$rssi")

            // Verify service UUID
            val scanRecord = result.scanRecord
            val hasOurService = scanRecord?.getServiceUuids()?.any { it.uuid == SERVICE_UUID } == true
            if (!hasOurService && BuildConfig.DEBUG) {
                LogUtil.d("BleManager", "  Not our service, ignoring")
                return
            }

            val hasConnectionRequest = scanRecord?.getServiceUuids()?.any { it.uuid == CONNECTION_REQUEST_UUID } == true
            val mfrData = scanRecord?.getManufacturerSpecificData(AIRSHARE_MANUFACTURER_ID)
            val bleIdentifier = mfrData?.joinToString("") { "%02x".format(it) } ?: device.address

            // ✅ INSTANT PROXIMITY DETECTION - No waiting!
            val smoothedRssi = getSmoothedRssi(device.address, rssi)
            val isClose = smoothedRssi > PROXIMITY_THRESHOLD

            val peer = Peer(
                id = device.address,
                name = deviceName,
                rssi = smoothedRssi,
                isProximityTriggered = isClose,
                lastSeenMs = System.currentTimeMillis(),
                bleIdentifier = bleIdentifier
            )

            // ✅ Trigger immediately when close, with cooldown to avoid spam
            // ONLY if the peer is actively requesting a connection
            if (isClose && hasConnectionRequest && !recentlyTriggeredPeers.contains(device.address)) {
                LogUtil.i("BleManager", "🎯 PROXIMITY INSTANT: ${peer.name} (RSSI=$rssi)")
                recentlyTriggeredPeers.add(device.address)
                onProximityDetected(peer)
                
                // Remove from cooldown after delay
                managerScope.launch {
                    delay(TRIGGER_COOLDOWN_MS)
                    recentlyTriggeredPeers.remove(device.address)
                }
            }

            updatePeers(peer)
        }

        override fun onScanFailed(errorCode: Int) {
            LogUtil.e("BleManager", "Scan failed: $errorCode")
            when (errorCode) {
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    LogUtil.e("BleManager", "App registration failed - try restarting phone")
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                    LogUtil.e("BleManager", "Internal error - Bluetooth may be off")
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                    LogUtil.e("BleManager", "BLE scan not supported on this device")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            LogUtil.i("BleManager", "✅ Advertising started successfully")
            advertisingRetryCount = 0
        }

        override fun onStartFailure(errorCode: Int) {
            LogUtil.e("BleManager", "Advertising failed: $errorCode")
            // Retry logic
            if (advertisingRetryCount < MAX_ADVERTISING_RETRIES) {
                advertisingRetryCount++
                managerScope.launch {
                    delay(2000)
                    startAdvertising()
                }
            }
        }
    }

    private fun updatePeers(newPeer: Peer) {
        val current = _discoveredPeers.value.toMutableList()
        val index = current.indexOfFirst { it.id == newPeer.id }
        if (index != -1) {
            current[index] = newPeer
        } else {
            current.add(newPeer)
        }
        val now = System.currentTimeMillis()
        _discoveredPeers.value = current.filter { now - it.lastSeenMs < 15000 }
    }

    fun startDiscovery() {
        LogUtil.i("BleManager", "🚀 Starting discovery (with adaptive scanning)")

        if (adapter == null) {
            LogUtil.e("BleManager", "Bluetooth adapter is null")
            return
        }
        if (!adapter!!.isEnabled) {
            LogUtil.e("BleManager", "Bluetooth is disabled. Please enable Bluetooth.")
            return
        }
        if (!hasRequiredPermissions()) {
            LogUtil.e("BleManager", "Missing permissions for BLE")
            return
        }

        if (isDiscoveryActive) {
            LogUtil.w("BleManager", "Discovery already running")
            return
        }

        isDiscoveryActive = true
        isLowPowerMode = false
        _discoveredPeers.value = emptyList()
        recentlyTriggeredPeers.clear()

        discoveryJob = managerScope.launch {
            // Phase 1: 5 minutes of continuous adaptive scanning
            val startTime = System.currentTimeMillis()
            
            while (isActive && isDiscoveryActive) {
                try {
                    val now = System.currentTimeMillis()
                    val peerActivity = _discoveredPeers.value.any { now - it.lastSeenMs < 60000 }
                    
                    // Transition to Low Power Mode if 5 mins passed and no active peers
                    if (!isLowPowerMode && now - startTime > ACTIVE_SCAN_DURATION && !peerActivity) {
                        LogUtil.i("BleManager", "🔋 Entering Low Power Mode (Duty Cycle)")
                        isLowPowerMode = true
                    }

                    startAdvertising()
                    startScanning()
                    
                    if (isLowPowerMode) {
                        // Duty Cycle: Scan for 5 seconds
                        delay(DUTY_CYCLE_SCAN_TIME)
                        stopScanningOnly()

                        // 🛠️ INTERRUPTIBLE SLEEP: 30s gap broken into 1s chunks
                        // Isse battery bhi bachegi aur app turant "Stop" hogi
                        val gapTicks = (DUTY_CYCLE_INTERVAL / 1000).toInt()
                        repeat(gapTicks) {
                            if (!isDiscoveryActive || !isLowPowerMode) return@repeat 
                            delay(1000) 
                            yield() 
                        }
                    } else {
                        // Active Mode: Continuous-ish scanning
                        delay(SCAN_DURATION_MS)
                        stopScanningOnly()
                        delay(SCAN_INTERVAL_MS)
                    }
                } catch (e: CancellationException) {
                    LogUtil.d("BleManager", "Discovery cancelled")
                    throw e
                } catch (e: Exception) {
                    LogUtil.e("BleManager", "Discovery cycle error", e)
                    delay(DUTY_CYCLE_SCAN_TIME)
                }
            }
            LogUtil.i("BleManager", "Discovery stopped")
        }
    }

    private fun startAdvertising() {
        if (isAdvertising) return
        if (adapter?.bluetoothLeAdvertiser == null) {
            LogUtil.e("BleManager", "No BLE advertiser available")
            return
        }

        // 1. Configure High-Power, Low-Latency Settings for "Instant" feel
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // Maximum range/strength
            .build()

        // Process the Session ID into byte format
        val idBytes = sessionId.take(12).chunked(2).mapNotNull {
            it.toIntOrNull(16)?.toByte()
        }.toByteArray()

        // 2. Primary Advertising Data: Must stay under 31 bytes
        val dataBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID)) // Essential for filtering
            .setIncludeTxPowerLevel(false) // Save space
            
        if (isConnectionRequesting) {
            dataBuilder.addServiceUuid(ParcelUuid(CONNECTION_REQUEST_UUID))
        }
        val data = dataBuilder.build()

        // 3. Scan Response Data: Move heavy items here
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true) // User-friendly name
            .addManufacturerData(AIRSHARE_MANUFACTURER_ID, idBytes) // AirShare specific ID
            .build()

        // Start the broadcast
        adapter?.bluetoothLeAdvertiser?.startAdvertising(
            settings, 
            data, 
            scanResponse, 
            advertiseCallback
        )
        isAdvertising = true
        LogUtil.d("BleManager", "Advertising started successfully with split payloads")
    }

    private fun startScanning() {
        if (isScanning) return
        if (adapter?.bluetoothLeScanner == null) {
            LogUtil.e("BleManager", "No BLE scanner available")
            return
        }

        // Use only service UUID filter for maximum compatibility
        val serviceFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // ✅ FASTEST SCAN MODE
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)  // Immediate reporting
            .build()

        adapter?.bluetoothLeScanner?.startScan(listOf(serviceFilter), settings, scanCallback)
        isScanning = true
        LogUtil.d("BleManager", "Scanning started")
    }

    private fun stopScanningOnly() {
        if (isScanning) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            LogUtil.d("BleManager", "Scanning stopped")
        }
    }

    private fun stopAdvertising() {
        if (isAdvertising) {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            LogUtil.d("BleManager", "Advertising stopped")
        }
    }

    fun stopDiscovery() {
        LogUtil.i("BleManager", "Stopping discovery")
        isDiscoveryActive = false
        discoveryJob?.cancel()
        stopAdvertising()
        stopScanningOnly()
        _discoveredPeers.value = emptyList()
        recentlyTriggeredPeers.clear()
        rssiHistory.clear()
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 8.1 and 9 need location permission
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ needs BLUETOOTH_SCAN and BLUETOOTH_CONNECT
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10-11: no specific BLE permissions required (but location needed for scan)
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun restartDiscovery() {
        LogUtil.i("BleManager", "🔄 Restarting discovery manually")
        stopDiscovery()
        // Small delay to ensure the hardware finishes stopping before starting again
        managerScope.launch {
            delay(500)
            startDiscovery()
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun resetToActiveMode() {
        if (isLowPowerMode) {
            LogUtil.i("BleManager", "⚡ Manual Reset: Switching to Active Mode")
            isLowPowerMode = false
            // The discovery loop will pick up the mode change on its next tick (within 1s)
        }
    }

    fun isLowPowerMode(): Boolean = isLowPowerMode

    fun setConnectionRequestMode(requesting: Boolean) {
        if (isConnectionRequesting == requesting) return
        isConnectionRequesting = requesting
        if (isAdvertising) {
            stopAdvertising()
            startAdvertising()
        }
    }
}
