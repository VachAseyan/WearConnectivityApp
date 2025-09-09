package com.mywearapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.example.mobilecommunication.MobileCommunicationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MobileCommunicationModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

    private var communicationManager: MobileCommunicationManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var isInitialized = false

    companion object {
        private const val TAG = "MobileCommunicationModule"
    }

    override fun getName(): String = "MobileCommunicationModule"

    @ReactMethod
    fun addListener(eventName: String) {
        Log.d(TAG, "addListener: $eventName")
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        Log.d(TAG, "removeListeners: $count")
    }

    @ReactMethod
    fun initialize(promise: Promise) {
        Log.d(TAG, "initialize called")
        
        if (isInitialized) {
            promise.resolve(true)
            return
        }

        mainHandler.post {
            try {
                communicationManager = MobileCommunicationManager(reactContext)

                // Set up callbacks BEFORE registering listeners
                communicationManager?.onMessageReceived = { message: String, fromWear: Boolean ->
                    Log.d(TAG, "onMessageReceived: $message, fromWear: $fromWear")
                    sendEvent("onMessageReceived", Arguments.createMap().apply {
                        putString("message", message)
                        putBoolean("fromWear", fromWear)
                    })
                }

                communicationManager?.onWearableConnected = { nodePresent: Boolean, ackReceived: Boolean ->
                    Log.d(TAG, "onWearableConnected: nodePresent=$nodePresent, ackReceived=$ackReceived")
                    sendEvent("onWearableConnected", Arguments.createMap().apply {
                        putBoolean("nodePresent", nodePresent)
                        putBoolean("ackReceived", ackReceived)
                    })
                }

                communicationManager?.onMessageSent = { success: Boolean, message: String? ->
                    Log.d(TAG, "onMessageSent: success=$success, message=$message")
                    sendEvent("onMessageSent", Arguments.createMap().apply {
                        putBoolean("success", success)
                        putString("message", message ?: "")
                    })
                }

                // Register listeners after setting up callbacks
                communicationManager?.registerListeners()
                isInitialized = true
                
                Log.d(TAG, "Module initialized successfully")
                promise.resolve(true)
                
            } catch (e: Exception) {
                Log.e(TAG, "Initialize failed: ${e.message}", e)
                promise.reject("INIT_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun sendMessageToWearable(message: String, promise: Promise) {
        Log.d(TAG, "sendMessageToWearable: $message")
        
        if (!isInitialized || communicationManager == null) {
            promise.reject("NOT_INITIALIZED", "Module not initialized")
            return
        }

        mainHandler.post {
            try {
                communicationManager?.sendMessageToWearable(message)
                // Promise resolve անենք անմիջապես, իսկ actual result-ը կգա onMessageSent callback-ով
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "sendMessageToWearable failed: ${e.message}", e)
                promise.reject("SEND_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
fun checkWearableConnection(promise: Promise) {
    Log.d(TAG, "checkWearableConnection called")
    
    if (!isInitialized || communicationManager == null) {
        promise.reject("NOT_INITIALIZED", "Module not initialized")
        return
    }

    coroutineScope.launch {
        try {
            Log.d(TAG, "Starting connection check with longer timeout")
            val result = communicationManager?.checkWearableConnection()
            
            mainHandler.post {
                val resultArray = Arguments.createArray()
                resultArray.pushBoolean(result?.get(0) ?: false) // nodePresent
                resultArray.pushBoolean(result?.get(1) ?: false) // ackReceived
                
                Log.d(TAG, "checkWearableConnection result: nodePresent=${result?.get(0)}, ackReceived=${result?.get(1)}")
                promise.resolve(resultArray)
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkWearableConnection failed: ${e.message}", e)
            mainHandler.post {
                promise.reject("CHECK_FAILED", e.message, e)
            }
        }
    }
}

    @ReactMethod
    fun cleanup() {
        Log.d(TAG, "cleanup called")
        mainHandler.post {
            try {
                communicationManager?.unregisterListeners()
                communicationManager = null
                isInitialized = false
                Log.d(TAG, "Cleanup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup failed: ${e.message}", e)
            }
        }
    }

    @ReactMethod
    fun isModuleInitialized(callback: Callback) {
        callback.invoke(isInitialized)
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        try {
            if (reactContext.hasActiveCatalystInstance()) {
                Log.d(TAG, "sendEvent: $eventName")
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(eventName, params)
            } else {
                Log.w(TAG, "Cannot send event $eventName - no active catalyst instance")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendEvent failed: ${e.message}", e)
        }
    }

    override fun onCatalystInstanceDestroy() {
        Log.d(TAG, "onCatalystInstanceDestroy called")
        cleanup()
        super.onCatalystInstanceDestroy()
    }
}