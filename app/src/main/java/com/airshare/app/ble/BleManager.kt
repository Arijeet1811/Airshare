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
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import com.airshare.app.model.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val SERVICE_UUID = UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
    private val PROXIMITY_THRESHOLD = -70 // Slightly relaxed from -65 for better real-world performance

    private val managerScope = CoroutineScope(Dispatchers.Main)
    private var discoveryJob: Job? = null

    private var isScanning = false
    private var isAdvertising = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.address ?: "Unknown Device"
            val rssi = result.rssi

            val mfrData = result.scanRecord?.getManufacturerSpecificData(0xFFFF)
            val bleIdentifier = mfrData?.joinToString("") { "%02x".format(it) } ?: device.address

            val isClose = rssi > PROXIMITY_THRESHOLD

            val peer = Peer(
                id = device.address,
                name = deviceName,
                rssi = rssi,
                isProximityTriggered = isClose,
                lastSeenMs = System.currentTimeMillis(),
                bleIdentifier = bleIdentifier
            )

            if (isClose) {
                onProximityDetected(peer)
            }

            updatePeers(peer)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleManager", "Scan failed with error code: $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i("BleManager", "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BleManager", "BLE Advertising failed: $errorCode")
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

        // Cleanup old peers
        val now = System.currentTimeMillis()
        _discoveredPeers.value = current.filter { now - it.lastSeenMs < 15000 } // 15 seconds timeout
    }

    fun startDiscovery() {
        if (adapter == null || !adapter.isEnabled) {
            Log.w("BleManager", "Bluetooth is disabled")
            return
        }

        stopDiscovery() // Clean previous job
        
        // Clear old peers immediately
        _discoveredPeers.value = emptyList()

        discoveryJob = managerScope.launch {
            while (true) {
                startAdvertising()
                startScanning()

                // Adaptive duty cycle for battery saving
                delay(if (_discoveredPeers.value.isEmpty()) 8000L else 12000L)

                stopScanningOnly()

                // Longer sleep when no devices around
                if (_discoveredPeers.value.isEmpty()) {
                    delay(18000L) // ~30s cycle when idle
                } else {
                    delay(8000L)
                }
            }
        }
    }

    private fun startAdvertising() {
        if (isAdvertising || adapter?.bluetoothLeAdvertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val manufacturerData = Settings.Secure.getString(context.contentResolver, "android_id")
            ?.take(12)?.chunked(2)?.map { it.toIntOrNull(16)?.toByte() ?: 0x00 }?.toByteArray()
            ?: ByteArray(6)

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(true)
            .addManufacturerData(0xFFFF, manufacturerData)
            .build()

        adapter.bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true
    }

    private fun startScanning() {
        if (isScanning || adapter?.bluetoothLeScanner == null) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
    }

    private fun stopScanningOnly() {
        if (isScanning) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private fun stopAdvertising() {
        if (isAdvertising) {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        stopAdvertising()
        stopScanningOnly()
        // Clear old peers
        _discoveredPeers.value = emptyList()
    }

    fun restartDiscovery() {
        stopDiscovery()
        managerScope.launch {
            delay(600)
            startDiscovery()
        }
    }
}
