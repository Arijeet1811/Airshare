package com.airshare.app.model

import kotlinx.coroutines.CompletableDeferred

sealed class TransferState {
    object Idle : TransferState()
    data class Request(
        val fileName: String,
        val fileSize: Long,
        val fileCount: Int = 1,
        val response: CompletableDeferred<Boolean>
    ) : TransferState()
    data class SecurityVerification(
        val ourFingerprint: String,
        val peerFingerprint: String,
        val response: CompletableDeferred<Boolean>
    ) : TransferState()
    data class Transferring(val progress: Float, val fileName: String) : TransferState()
    object Success : TransferState()
    data class Error(val message: String) : TransferState()
}
