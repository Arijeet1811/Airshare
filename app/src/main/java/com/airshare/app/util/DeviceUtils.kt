package com.airshare.app.util

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceUtils {
    fun getDeviceName(context: Context): String {
        return try {
            val name = Settings.Global.getString(context.contentResolver, "device_name")
            if (name.isNullOrBlank()) {
                "${Build.MANUFACTURER} ${Build.MODEL}"
            } else {
                name
            }
        } catch (e: Exception) {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        }
    }
}
