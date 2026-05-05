package com.airshare.app.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WifiDirectManager(
    private val context: Context,
    private val onConnectionInfoAvailable: (WifiP2pInfo) -> Unit
) : WifiP2pManager.ChannelListener {
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), this)

    private val _discoveredWifiDevices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredWifiDevices: StateFlow<List<WifiP2pDevice>> = _discoveredWifiDevices.asStateFlow()

    private var receiver: WifiDirectBroadcastReceiver? = null

    override fun onChannelDisconnected() {
        Log.d("WifiDirect", "Channel disconnected")
    }

    fun registerReceiver(context: Context) {
        if (receiver != null) return
        receiver = WifiDirectBroadcastReceiver()
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)
    }

    fun unregisterReceiver(context: Context) {
        try {
            receiver?.let {
                context.unregisterReceiver(it)
                receiver = null
            }
        } catch (e: Exception) {
            Log.e("WifiDirect", "Error unregistering receiver: ${e.message}")
        }
    }

    inner class WifiDirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.d("WifiDirect", "Wi-Fi P2P is enabled")
                    } else {
                        Log.d("WifiDirect", "Wi-Fi P2P is disabled")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d("WifiDirect", "Peers changed")
                    requestPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val p2pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    if (p2pInfo?.groupFormed == true) {
                        Log.d("WifiDirect", "P2P group formed, requesting connection info")
                        requestConnectionInfo()
                    } else {
                        Log.d("WifiDirect", "P2P group dissolved or not formed")
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    Log.d("WifiDirect", "This device changed: ${device?.deviceName}")
                }
            }
        }
    }

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

    fun cleanup() {
        manager?.removeGroup(channel, null)
        manager?.cancelConnect(channel, null)
        manager?.stopPeerDiscovery(channel, null)
    }
}
