# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/runner/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# For more details, see
#   http://developer.android.com/guide/developing/tools-proguard.html

# Add any custom keep rules here that are specific to your project
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

# Keep our model classes
-keep class com.airshare.app.model.** { *; }

# Keep service binding
-keep class com.airshare.app.service.** { *; }

# Don't obfuscate BLE callbacks
-keep class * extends android.bluetooth.le.ScanCallback { *; }
-keep class * extends android.bluetooth.le.ScanResult { *; }
-keep class * extends android.bluetooth.le.AdvertiseCallback { *; }

# Keep LogUtil methods
-keepclassmembers class com.airshare.app.util.LogUtil {
    public static void **(...);
}
