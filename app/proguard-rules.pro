# Add project specific ProGuard rules here.
-keep class com.airshare.app.model.** { *; }

# Keep encryption classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Keep JSON parsing
-keep class org.json.** { *; }
-keepclassmembers class * {
    @org.json.JSONObject *;
}

# Keep Kotlin reflection for coroutines
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, EnclosingMethod

# Keep service binding
-keep class com.airshare.app.service.** { *; }

# Don't obfuscate BLE callbacks
-keep class * extends android.bluetooth.le.ScanCallback { *; }
-keep class * extends android.bluetooth.le.AdvertiseCallback { *; }

# Keep LogUtil methods
-keepclassmembers class com.airshare.app.util.LogUtil {
    public static void **(...);
}
