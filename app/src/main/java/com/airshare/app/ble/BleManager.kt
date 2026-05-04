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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val advertiser = adapter?.bluetoothLeAdvertiser
    private val scanner = adapter?.bluetoothLeScanner

    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val SERVICE_UUID = UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb") // AirShare Service UUID
    private val PROXIMITY_THRESHOLD = -65 // RSSI threshold for "close" proximity

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.address ?: "Unknown"
            val rssi = result.rssi
            val isClose = rssi > PROXIMITY_THRESHOLD

            val newPeer = Peer(
                id = device.address,
                name = deviceName,
                rssi = rssi,
                isProximityTriggered = isClose
            )

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
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
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
