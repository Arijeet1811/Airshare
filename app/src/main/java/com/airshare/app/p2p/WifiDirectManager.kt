package com.airshare.app.p2p

import android.content.Context
import android.net.wifi.p2p.*
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WifiDirectManager(
    private val context: Context,
    private val onConnectionInfoAvailable: (WifiP2pInfo) -> Unit
) {
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null)

    private val _discoveredWifiDevices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredWifiDevices: StateFlow<List<WifiP2pDevice>> = _discoveredWifiDevices.asStateFlow()

    fun initiateDiscovery() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WifiDirect", "Discovery initiated")
                requestPeers()
            }

            override fun onFailure(reason: Int) {
                Log.e("WifiDirect", "Discovery failed: $reason")
            }
        })
    }

    private fun requestPeers() {
        manager?.requestPeers(channel) { peersList ->
            _discoveredWifiDevices.value = peersList.deviceList.toList()
        }
    }

    fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WifiDirect", "P2P Connection initiated")
            }

            override fun onFailure(reason: Int) {
                Log.e("WifiDirect", "P2P Connection failed: $reason")
            }
        })
    }

    fun requestConnectionInfo() {
        manager?.requestConnectionInfo(channel) { info ->
            if (info.groupFormed) {
                onConnectionInfoAvailable(info)
            }
        }
    }

    fun discoverPeers(onPeersDiscovered: (WifiP2pDeviceList) -> Unit) {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestPeers(channel) { peers ->
                    onPeersDiscovered(peers)
                }
            }
            override fun onFailure(reason: Int) {}
        })
    }
}
