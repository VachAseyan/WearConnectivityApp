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
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MobileCommunicationModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

    private var communicationManager: MobileCommunicationManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // State tracking
    @Volatile
    private var ackReceivedState: Boolean = false
    @Volatile  
    private var isInitialized: Boolean = false
    private val ackReceived = AtomicBoolean(false)

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
        
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            promise.resolve(true)
            return
        }

        try {
            communicationManager = MobileCommunicationManager(reactContext)

            // Message received from Wear
            communicationManager?.onMessageReceived = { message: String, fromWear: Boolean ->
                Log.d(TAG, "onMessageReceived: message='$message', fromWear=$fromWear")

                // Handle ACK response
                if (message.equals("ack", ignoreCase = true)) {
                    ackReceived.set(true)
                    ackReceivedState = true
                    Log.d(TAG, "ACK received from wearable ✅")
                }

                // Send event to React Native
                mainHandler.post {
                    sendEvent("onMessageReceived", Arguments.createMap().apply {
                        putString("message", message)
                        putBoolean("fromWear", fromWear)
                    })
                }
            }

            // Wearable connected
            communicationManager?.onWearableConnected = { nodePresent: Boolean, ackReceivedFromManager: Boolean ->
                Log.d(TAG, "onWearableConnected: nodePresent=$nodePresent, ackReceived=$ackReceivedFromManager")
                
                mainHandler.post {
                    sendEvent("onWearableConnected", Arguments.createMap().apply {
                        putBoolean("nodePresent", nodePresent)
                        putBoolean("ackReceived", ackReceivedFromManager)
                    })
                }
            }

            // Message sent confirmation
            communicationManager?.onMessageSent = { success: Boolean, message: String? ->
                Log.d(TAG, "onMessageSent: success=$success, message='$message'")
                
                mainHandler.post {
                    sendEvent("onMessageSent", Arguments.createMap().apply {
                        putBoolean("success", success)
                        putString("message", message ?: "")
                    })
                }
            }

            // Register listeners
            communicationManager?.registerListeners()
            isInitialized = true
            
            Log.d(TAG, "Communication manager initialized successfully")
            promise.resolve(true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Initialize failed: ${e.message}", e)
            promise.reject("INIT_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun sendMessageToWearable(message: String, promise: Promise) {
        Log.d(TAG, "sendMessageToWearable called with: '$message'")
        
        if (communicationManager == null || !isInitialized) {
            Log.e(TAG, "Communication manager not initialized")
            promise.reject("NOT_INITIALIZED", "Communication manager not initialized")
            return
        }

        try {
            communicationManager?.sendMessageToWearable(message)
            Log.d(TAG, "Message queued for sending to wearable")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}", e)
            promise.reject("SEND_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun checkWearableConnection(promise: Promise) {
        Log.d(TAG, "checkWearableConnection called")
        
        if (communicationManager == null || !isInitialized) {
            promise.reject("NOT_INITIALIZED", "Communication manager not initialized")
            return
        }

        coroutineScope.launch {
            try {
                // Reset ACK state before checking
                ackReceived.set(false)
                ackReceivedState = false
                
                val result = communicationManager?.checkWearableConnection()
                val nodePresent = result?.get(0) ?: false

                // If node is present, proactively send repeated pings and wait for ACK (helps with timing)
                var ackReceivedFinal = false
                if (nodePresent) {
                    try {
                        Log.d(TAG, "checkWearableConnection: node present, starting probe pings")

                        var waited = 0
                        val maxWait = 3000 // 3 seconds quick check
                        val interval = 100
                        val resendEvery = 500 // resend ping every 500ms

                        while (waited < maxWait && !ackReceived.get()) {
                            if (waited % resendEvery == 0) {
                                Log.d(TAG, "checkWearableConnection: sending probe ping at ${waited}ms")
                                communicationManager?.sendMessageToWearable("ping")
                            }
                            kotlinx.coroutines.delay(interval.toLong())
                            waited += interval
                        }
                        ackReceivedFinal = ackReceived.get()
                        Log.d(TAG, "checkWearableConnection: probe result ack=$ackReceivedFinal after ${waited}ms")
                    } catch (e: Exception) {
                        Log.e(TAG, "checkWearableConnection: probe ping failed: ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "checkWearableConnection: node not present, skipping probe ping")
                }

                Log.d(TAG, "Connection check result: nodePresent=$nodePresent, ackReceived=$ackReceivedFinal")

                withContext(Dispatchers.Main) {
                    val responseMap = Arguments.createMap().apply {
                        putBoolean("nodePresent", nodePresent)
                        putBoolean("ackReceived", ackReceivedFinal)
                    }
                    promise.resolve(responseMap)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Connection check failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    promise.reject("CHECK_FAILED", e.message, e)
                }
            }
        }
    }

    @ReactMethod
    fun pingWearable(promise: Promise) {
        Log.d(TAG, "pingWearable called")
        
        if (communicationManager == null || !isInitialized) {
            promise.reject("NOT_INITIALIZED", "Communication manager not initialized")
            return
        }

        coroutineScope.launch {
            try {
                // Reset ACK state
                ackReceived.set(false)
                ackReceivedState = false
                
                // Send ping(s) with retry while waiting
                Log.d(TAG, "Before sending ping, ackReceived: ${ackReceived.get()}")
                communicationManager?.sendMessageToWearable("ping")
                Log.d(TAG, "Ping sent, starting wait loop")

                var waitTime = 0
                val maxWait = 3000 // 3 seconds
                val checkInterval = 100 // 100ms
                val resendEvery = 500 // resend ping every 500ms while waiting

                while (waitTime < maxWait && !ackReceived.get()) {
                    if (waitTime % resendEvery == 0 && waitTime != 0) {
                        Log.d(TAG, "Resending ping at ${waitTime}ms")
                        communicationManager?.sendMessageToWearable("ping")
                    }
                    Log.d(TAG, "Waiting for ACK, elapsed: ${waitTime}ms")
                    kotlinx.coroutines.delay(checkInterval.toLong())
                    waitTime += checkInterval
                }
                
                val ackReceivedFinal = ackReceived.get()
                Log.d(TAG, "Ping result: ackReceived=$ackReceivedFinal after ${waitTime}ms")

                withContext(Dispatchers.Main) {
                    val responseMap = Arguments.createMap().apply {
                        putBoolean("ackReceived", ackReceivedFinal)
                        putInt("responseTime", waitTime)
                    }
                    promise.resolve(responseMap)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Ping failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    promise.reject("PING_FAILED", e.message, e)
                }
            }
        }
    }

    @ReactMethod
    fun getConnectionStatus(promise: Promise) {
        Log.d(TAG, "getConnectionStatus called")
        
        try {
            val statusMap = Arguments.createMap().apply {
                putBoolean("initialized", isInitialized)
                putBoolean("managerExists", communicationManager != null)
                putBoolean("lastAckReceived", ackReceivedState)
            }
            promise.resolve(statusMap)
        } catch (e: Exception) {
            Log.e(TAG, "getConnectionStatus failed: ${e.message}", e)
            promise.reject("STATUS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun cleanup() {
        Log.d(TAG, "cleanup called")
        try {
            communicationManager?.unregisterListeners()
            communicationManager = null
            isInitialized = false
            ackReceivedState = false
            ackReceived.set(false)
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