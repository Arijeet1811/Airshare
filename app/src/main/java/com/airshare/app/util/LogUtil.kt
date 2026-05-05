package com.airshare.app.util

import android.util.Log
import com.airshare.app.BuildConfig

object LogUtil {
    private val IS_DEBUG = BuildConfig.DEBUG
    
    fun d(tag: String, message: String) {
        if (IS_DEBUG) Log.d(tag, message)
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
    
    fun sensitive(tag: String, message: String) {
        if (IS_DEBUG) {
            Log.d(tag, message)
        } else {
            // Log hash instead of actual data in production
            val hash = message.hashCode().toString()
            Log.d(tag, "Sensitive data hash: $hash")
        }
    }
}
