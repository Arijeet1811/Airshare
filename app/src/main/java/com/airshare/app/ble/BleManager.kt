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
    private val onRoleSelectionNeeded: (Peer) -> Unit,
    private val onRoleConflict: (Peer) -> Unit,
    private val onComplementaryRole: (Peer) -> Unit
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    // ✅ SINGLE Service UUID - bass filtering ke liye kaafi hai
    private val SERVICE_UUID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private val PROXIMITY_THRESHOLD = -65  // Made stricter for faster trigger

    companion object {
        private const val AIRSHARE_MANUFACTURER_ID = 0x07CD
        private const val SCAN_DURATION_MS = 3000L
        private const val SCAN_INTERVAL_MS = 1000L
        private const val MAX_ADVERTISING_RETRIES = 3
        
        // Role constants
        const val ROLE_UNDECIDED: Byte = 0
        const val ROLE_SENDER: Byte = 1
        const val ROLE_RECEIVER: Byte = 2
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
    private var mySelectedRole: Byte = ROLE_UNDECIDED
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

    /**
     * ✅ OPTIMIZED SCAN CALLBACK
     * Role extraction from manufacturer data - NO UUID check for role
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (isLowPowerMode) {
                LogUtil.i("BleManager", "Peer found! Switching to Active Mode.")
                isLowPowerMode = false
            }

            val device = result.device
            val scanRecord = result.scanRecord ?: return
            
            // ✅ Quick filter: Check our service UUID first
            val hasOurService = scanRecord.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if (!hasOurService) return

            val deviceName = scanRecord.deviceName 
                ?: device.name 
                ?: "Unknown"
            val rssi = result.rssi

            // ✅ Extract role from manufacturer data
            var peerRole: Byte = ROLE_UNDECIDED
            var bleIdentifier = device.address // fallback
            
            scanRecord.getManufacturerSpecificData(AIRSHARE_MANUFACTURER_ID)?.let { data ->
                if (data.size >= 7) {
                    // First 6 bytes = device identifier
                    bleIdentifier = data.copyOfRange(0, 6).joinToString("") { "%02x".format(it) }
                    // 7th byte = role
                    peerRole = data[6]
                } else if (data.size == 6) {
                    bleIdentifier = data.copyOfRange(0, 6).joinToString("") { "%02x".format(it) }
                    peerRole = ROLE_UNDECIDED
                }
            }

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

            updatePeers(peer)

            // ✅ Role-based logic (only when close enough)
            if (isClose && !recentlyTriggeredPeers.contains(device.address)) {
                val myRole = mySelectedRole
                
                when {
                    // Both undecided → show role selection
                    myRole == ROLE_UNDECIDED && peerRole == ROLE_UNDECIDED -> {
                        LogUtil.i("BleManager", "🎯 Both undecided - showing role selection")
                        recentlyTriggeredPeers.add(device.address)
                        onRoleSelectionNeeded(peer)
                        managerScope.launch {
                            delay(TRIGGER_COOLDOWN_MS)
                            recentlyTriggeredPeers.remove(device.address)
                        }
                    }
                    // Same role chosen → conflict
                    myRole != ROLE_UNDECIDED && peerRole == myRole -> {
                        LogUtil.w("BleManager", "⚠️ Role conflict! Both are ${if(myRole == ROLE_SENDER) "SENDER" else "RECEIVER"}")
                        recentlyTriggeredPeers.add(device.address)
                        onRoleConflict(peer)
                        managerScope.launch {
                            delay(TRIGGER_COOLDOWN_MS)
                            recentlyTriggeredPeers.remove(device.address)
                        }
                    }
                    // I'm receiver, peer is sender → complementary!
                    myRole == ROLE_RECEIVER && peerRole == ROLE_SENDER -> {
                        LogUtil.i("BleManager", "🔗 Complementary roles detected! Starting connection...")
                        recentlyTriggeredPeers.add(device.address)
                        onComplementaryRole(peer)
                        managerScope.launch {
                            delay(TRIGGER_COOLDOWN_MS)
                            recentlyTriggeredPeers.remove(device.address)
                        }
                    }
                    // I'm sender, peer is receiver → sender just waits (no action needed)
                    myRole == ROLE_SENDER && peerRole == ROLE_RECEIVER -> {
                        LogUtil.d("BleManager", "👀 Sender detected receiver - waiting for connection")
                        // Receiver will initiate WiFi Direct connection
                    }
                }
            }
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
            LogUtil.i("BleManager", "✅ Advertising started (role=${mySelectedRole})")
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

    /**
     * ✅ OPTIMIZED ADVERTISING - Fits in 31 bytes
     * 
     * Advertisement Data (max 31 bytes):
     *   - Flags: 3 bytes
     *   - Service UUID (128-bit): 18 bytes
     *   Total: ~21 bytes ✅
     * 
     * Scan Response (max 31 bytes):
     *   - Device Name: variable (trimmed if needed)
     *   - Manufacturer Data: 2 (ID) + 7 (data) = 9 bytes
     *   Total: name_length + 9 bytes
     *   If name > 22 chars, we truncate it
     */
    private fun startAdvertising() {
        if (isAdvertising) return
        if (adapter?.bluetoothLeAdvertiser == null) {
            LogUtil.e("BleManager", "No BLE advertiser available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // ✅ Advertisement Data: ONLY Service UUID (well within 31 bytes)
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .build()

        // ✅ Scan Response: Device Name + Manufacturer Data (role encoded)
        val idBytes = sessionId.take(12).chunked(2).mapNotNull {
            it.toIntOrNull(16)?.toByte()
        }.toByteArray()
        
        // Manufacturer Data = 6 bytes device ID + 1 byte role = 7 bytes total
        val mfrData = idBytes + byteArrayOf(mySelectedRole)

        val scanResponseBuilder = AdvertiseData.Builder()
            .addManufacturerData(AIRSHARE_MANUFACTURER_ID, mfrData)
        
        // ✅ Include device name but TRUNCATE if needed
        val maxNameLength = 17
        val deviceName = adapter?.name ?: "AirShare"
        val truncatedName = if (deviceName.length > maxNameLength) {
            deviceName.take(maxNameLength - 1) + "…"
        } else {
            deviceName
        }
        
        if (truncatedName.length <= 20) {
            scanResponseBuilder.setIncludeDeviceName(true)
        } else {
            LogUtil.w("BleManager", "Device name too long, using generic name in scan response")
        }

        val scanResponse = scanResponseBuilder.build()

        adapter?.bluetoothLeAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        isAdvertising = true
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

    fun setRole(role: Byte) {
        mySelectedRole = role
        LogUtil.i("BleManager", "Role set to $role")
        // Restart advertising to include new role
        if (isAdvertising) {
            stopAdvertising()
            startAdvertising()
        }
    }

    fun resetRole() {
        setRole(ROLE_UNDECIDED)
    }

    fun getCurrentRole(): Byte = mySelectedRole

    fun isLowPowerMode(): Boolean = isLowPowerMode
}
