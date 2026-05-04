package com.airshare.app.model

sealed class TransferState {
    object Idle : TransferState()
    data class Transferring(val progress: Float, val fileName: String) : TransferState()
    object Success : TransferState()
    data class Error(val message: String) : TransferState()
}
