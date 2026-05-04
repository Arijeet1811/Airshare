package com.airshare.app.model

data class Peer(
    val id: String,
    val name: String,
    val rssi: Int,
    val isProximityTriggered: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val ip: String? = null
)
