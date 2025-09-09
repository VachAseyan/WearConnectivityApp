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

    companion object {
        private const val TAG = "MobileCommModule"
    }

    override fun getName(): String = "MobileCommunicationModule"

    // RN EventEmitter compatibility
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
        Log.e(TAG, "initialize")
        mainHandler.post {
            try {
                if (communicationManager == null) {
                    communicationManager = MobileCommunicationManager(reactContext)

                    communicationManager?.onMessageReceived = { message: String, fromWear: Boolean ->
                    Log.e(TAG, "onMessageReceived: $message, $fromWear")
                        sendEvent("onMessageReceived", Arguments.createMap().apply {
                            putString("message", message)
                            putBoolean("fromWear", fromWear)
                        })
                    }

                    communicationManager?.onWearableConnected = { nodePresent: Boolean, ackReceived: Boolean ->
                        Log.e(TAG, "onWearableConnected: $nodePresent, $ackReceived")
                        sendEvent("onWearableConnected", Arguments.createMap().apply {
                            putBoolean("nodePresent", nodePresent)
                            putBoolean("ackReceived", ackReceived)
                        })
                    }

                    communicationManager?.onMessageSent = { success: Boolean, message: String? ->
                        Log.e(TAG, "onMessageSent: $success, $message")
                        sendEvent("onMessageSent", Arguments.createMap().apply {
                            putBoolean("success", success)
                            putString("message", message)
                        })
                    }
                }

                communicationManager?.registerListeners()
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Initialize failed: ${e.message}", e)
                promise.reject("INIT_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun sendMessageToWearable(message: String, promise: Promise) {
    mainHandler.post {
        try {
            communicationManager?.sendMessageToWearable(message)
            promise.resolve(true) // resolve immediately
        } catch (e: Exception) {
            promise.reject("SEND_ERROR", e.message, e)
        }
    }
}

    @ReactMethod
    fun checkWearableConnection(promise: Promise) {
        Log.e(TAG, "checkWearableConnection")
        coroutineScope.launch {
            try {
                val result = communicationManager?.checkWearableConnection()
                val arr = Arguments.createArray()
                arr.pushBoolean(result?.get(0) ?: false) // nodePresent
                arr.pushBoolean(result?.get(1) ?: false) // ackReceived
                promise.resolve(arr)
            } catch (e: Exception) {
                promise.reject("CHECK_FAILED", e)
            }
        }
    }

    @ReactMethod
    fun cleanup() {
        mainHandler.post {
            try {
                communicationManager?.unregisterListeners()
                communicationManager = null
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup failed: ${e.message}", e)
            }
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        try {
            if (reactContext.hasActiveCatalystInstance()) {
                Log.e(TAG, "sendEvent: $eventName, $params")
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(eventName, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendEvent failed: ${e.message}", e)
        }
    }

    override fun onCatalystInstanceDestroy() {
        cleanup()
    }
}
