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

    // 👉 State to track ACK
    @Volatile
    private var ackReceivedState: Boolean = false

    companion object {
        private const val TAG = "MobileCommModule"
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
        
        try {
            if (communicationManager == null) {
                communicationManager = MobileCommunicationManager(reactContext)

                // 📩 Message received from Wear
                communicationManager?.onMessageReceived = { message: String, fromWear: Boolean ->
                    Log.d(TAG, "onMessageReceived: message=$message, fromWear=$fromWear")

                    // 👉 If message is ACK, update state
                    if (message == "ack") {
                        ackReceivedState = true
                        Log.d(TAG, "ACK received ✅")
                    }

                    sendEvent("onMessageReceived", Arguments.createMap().apply {
                        putString("message", message)
                        putBoolean("fromWear", fromWear)
                    })
                }

                // 🔗 Wear connected
                communicationManager?.onWearableConnected = { nodePresent: Boolean, ackReceived: Boolean ->
                    Log.d(TAG, "onWearableConnected: nodePresent=$nodePresent, ackReceived=$ackReceived")
                    sendEvent("onWearableConnected", Arguments.createMap().apply {
                        putBoolean("nodePresent", nodePresent)
                        putBoolean("ackReceived", ackReceived)
                    })
                }

                // 📤 Message sent
                communicationManager?.onMessageSent = { success: Boolean, message: String? ->
                    Log.d(TAG, "onMessageSent: success=$success, message=$message")
                    sendEvent("onMessageSent", Arguments.createMap().apply {
                        putBoolean("success", success)
                        putString("message", message ?: "")
                    })
                }

                // Register listeners
                communicationManager?.registerListeners()
                Log.d(TAG, "Communication manager initialized and listeners registered")
            }
            
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Initialize failed: ${e.message}", e)
            promise.reject("INIT_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun sendMessageToWearable(message: String, promise: Promise) {
        Log.d(TAG, "sendMessageToWearable called with: $message")
        
        if (communicationManager == null) {
            Log.e(TAG, "Communication manager not initialized")
            promise.reject("NOT_INITIALIZED", "Communication manager not initialized")
            return
        }

        try {
            communicationManager?.sendMessageToWearable(message)
            Log.d(TAG, "Message sent to manager")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}", e)
            promise.reject("SEND_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun checkWearableConnection(promise: Promise) {
        Log.d(TAG, "checkWearableConnection called")
        
        if (communicationManager == null) {
            promise.reject("NOT_INITIALIZED", "Communication manager not initialized")
            return
        }

        coroutineScope.launch {
            try {
                val result = communicationManager?.checkWearableConnection()
                val nodePresent = result?.get(0) ?: false
                val ackFlag = ackReceivedState

                Log.d(TAG, "Connection check result: nodePresent=$nodePresent, ackReceived=$ackFlag")

                val responseMap = Arguments.createMap().apply {
                    putBoolean("nodePresent", nodePresent)
                    putBoolean("ackReceived", ackFlag)
                }

                // 👉 Reset ack state after reporting (so next ping needs fresh ACK)
                ackReceivedState = false

                promise.resolve(responseMap)
            } catch (e: Exception) {
                Log.e(TAG, "Connection check failed: ${e.message}", e)
                promise.reject("CHECK_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun cleanup() {
        Log.d(TAG, "cleanup called")
        try {
            communicationManager?.unregisterListeners()
            communicationManager = null
            ackReceivedState = false
            Log.d(TAG, "Cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}", e)
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        try {
            if (reactContext.hasActiveCatalystInstance()) {
                Log.d(TAG, "Sending event: $eventName with params: $params")
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(eventName, params)
                Log.d(TAG, "Event sent successfully: $eventName")
            } else {
                Log.w(TAG, "Cannot send event $eventName - React context not active")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send event $eventName: ${e.message}", e)
        }
    }

    override fun onCatalystInstanceDestroy() {
        Log.d(TAG, "onCatalystInstanceDestroy called")
        cleanup()
        super.onCatalystInstanceDestroy()
    }
}
