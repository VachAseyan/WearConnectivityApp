# --- React Native պահպանում ---
-keep class com.facebook.react.** { *; }
-dontwarn com.facebook.react.**

# --- JavaScriptCore / Hermes պահպանում ---
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.jni.** { *; }
-dontwarn com.facebook.hermes.**
-dontwarn com.facebook.jni.**

# --- React Native DevSupport (debug only, բայց չջնջվի) ---
-keep class com.facebook.react.devsupport.** { *; }

# --- AndroidX + Multidex ---
-dontwarn androidx.multidex.**
-keep class androidx.multidex.** { *; }

# --- Kotlin coroutines պահպանում ---
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# --- Google Play Services (Wearable, Tasks, Base) ---
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.** { *; }

# --- Քո մոդուլները պահպանում ---
-keep class com.watchapp.** { *; }
-keep class com.mywearapp.** { *; }
-keepclassmembers class com.watchapp.** { *; }
-keepclassmembers class com.mywearapp.** { *; }

# --- External communication managers (avoid stripping) ---
-keep class com.example.mobilecommunication.** { *; }
-keep class com.example.wearablecommunication.** { *; }

# --- JNI methods (React Native bridge) ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Անմիջական callback-ները թողնված մնան ---
-keepclassmembers class * {
    @com.facebook.react.bridge.ReactMethod <methods>;
}
-keepclassmembers class * {
    @com.facebook.react.bridge.ReactModule <fields>;
}


# --- RN Event Emitter պահպանում ---
-keepclassmembers class * extends com.facebook.react.bridge.JavaScriptModule { *; }
-keepclassmembers class * extends com.facebook.react.bridge.NativeModule { *; }
-keepclassmembers class * extends com.facebook.react.bridge.ReactContextBaseJavaModule { *; }

# --- DeviceEventManagerModule (event emitter) ---
-keep class com.facebook.react.modules.core.DeviceEventManagerModule$RCTDeviceEventEmitter { *; }
