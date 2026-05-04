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
import android.os.ParcelUuid
import android.util.Log
import com.airshare.app.model.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val advertiser = adapter?.bluetoothLeAdvertiser
    private val scanner = adapter?.bluetoothLeScanner

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val SERVICE_UUID = UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb") // AirShare Service UUID
    private val PROXIMITY_THRESHOLD = -65 // RSSI threshold for "close" proximity
    private val managerScope = CoroutineScope(Dispatchers.Main)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.address ?: "Unknown"
            val rssi = result.rssi
            val isClose = rssi > PROXIMITY_THRESHOLD

            val existingPeer = _discoveredPeers.value.find { it.id == device.address }
            val wasAlreadyTriggered = existingPeer?.isProximityTriggered ?: false

            val newPeer = Peer(
                id = device.address,
                name = deviceName,
                rssi = rssi,
                isProximityTriggered = isClose,
                lastSeenMs = System.currentTimeMillis()
            )

            if (isClose && !wasAlreadyTriggered) {
                onProximityDetected(newPeer)
            }

            updatePeers(newPeer)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleManager", "Scan failed with error: $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("BleManager", "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BleManager", "BLE Advertising failed: $errorCode")
        }
    }

    private fun updatePeers(peer: Peer) {
        val currentList = _discoveredPeers.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == peer.id }
        if (index != -1) {
            currentList[index] = peer
        } else {
            currentList.add(peer)
        }
        _discoveredPeers.value = currentList
    }

    fun startDiscovery() {
        if (adapter == null || !adapter.isEnabled) return

        startAdvertising()
        startScanning()

        managerScope.launch {
            while (true) {
                delay(5000)
                val currentTime = System.currentTimeMillis()
                val currentList = _discoveredPeers.value
                val filteredList = currentList.filter { currentTime - it.lastSeenMs < 10000 }
                if (filteredList.size != currentList.size) {
                    _discoveredPeers.value = filteredList
                }
            }
        }
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopDiscovery() {
        advertiser?.stopAdvertising(advertiseCallback)
        scanner?.stopScan(scanCallback)
    }
}
