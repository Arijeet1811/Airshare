package com.airshare.app.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import android.provider.Settings
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
        Log.w("WifiDirect", "Channel disconnected")
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
            Log.e("WifiDirect", "Error unregistering receiver", e)
        }
    }

    inner class WifiDirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.i("WifiDirect", "Wi-Fi P2P state changed: $state")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val p2pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }

                    Log.d("WifiDirect", "Connection changed → groupFormed=${p2pInfo?.groupFormed}, isOwner=${p2pInfo?.isGroupOwner}")

                    if (p2pInfo?.groupFormed == true) {
                        requestConnectionInfo()  // Try immediately
                    }
                }
            }
        }
    }

    fun initiateDiscovery() {
        Log.i("WifiDirect", "Starting peer discovery...")
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WifiDirect", "Discovery started successfully")
                requestPeers()
            }
            override fun onFailure(reason: Int) {
                Log.e("WifiDirect", "Discovery failed: $reason")
            }
        })
    }

    private fun requestPeers() {
        manager?.requestPeers(channel) { peersList ->
            val devices = peersList.deviceList.toList()
            Log.d("WifiDirect", "Found ${devices.size} WiFi Direct devices")
            _discoveredWifiDevices.value = devices
        }
    }

    /**
     * ✅ UPDATED: Now accepts optional forceGroupOwner parameter
     */
    fun connect(device: WifiP2pDevice, forceGroupOwner: Boolean = false) {
        Log.i("WifiDirect", "Connecting to: ${device.deviceName} (${device.deviceAddress}), forceGroupOwner=$forceGroupOwner")

        manager?.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { performConnect(device, forceGroupOwner) }
            override fun onFailure(reason: Int) { performConnect(device, forceGroupOwner) } // No pending connect is also fine
        })
    }

    /**
     * ✅ UPDATED: forceGroupOwner parameter se group owner intent control hota hai
     */
    private fun performConnect(device: WifiP2pDevice, forceGroupOwner: Boolean = false) {
        val myId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        
        // If forceGroupOwner is true, this device will ALWAYS be Group Owner (intent = 15)
        // Otherwise, use tie-breaker logic based on device IDs
        val intentValue = if (forceGroupOwner) {
            15  // Always be Group Owner
        } else {
            if (myId.hashCode() > device.deviceAddress.hashCode()) 15 else 0
        }

        Log.d("WifiDirect", "Group Owner Intent: $intentValue (forceGroupOwner=$forceGroupOwner)")

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WifiP2pConfig.Builder()
                .setDeviceAddress(device.deviceAddress)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                .build().apply {
                    groupOwnerIntent = intentValue
                }
        } else {
            WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                groupOwnerIntent = intentValue
            }
        }

        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i("WifiDirect", "Connect request queued successfully (forceGroupOwner=$forceGroupOwner)")
            }
            override fun onFailure(reason: Int) {
                Log.e("WifiDirect", "Connect failed with reason: $reason")
            }
        })
    }

    fun requestConnectionInfo() {
        manager?.requestConnectionInfo(channel) { info ->
            Log.d("WifiDirect", "Connection Info: groupFormed=${info.groupFormed}, isOwner=${info.isGroupOwner}, ownerAddress=${info.groupOwnerAddress}")

            if (info.groupFormed) {
                if (info.groupOwnerAddress == null) {
                    Log.w("WifiDirect", "Group formed but owner address null → retrying in 800ms")
                    android.os.Handler(Looper.getMainLooper()).postDelayed({
                        requestConnectionInfo()
                    }, 800)
                } else {
                    onConnectionInfoAvailable(info)
                }
            }
        }
    }

    fun cleanup() {
        try {
            manager?.removeGroup(channel, null)
            manager?.cancelConnect(channel, null)
            manager?.stopPeerDiscovery(channel, null)
        } catch (e: Exception) {
            Log.e("WifiDirect", "Cleanup error", e)
        }
    }
}
