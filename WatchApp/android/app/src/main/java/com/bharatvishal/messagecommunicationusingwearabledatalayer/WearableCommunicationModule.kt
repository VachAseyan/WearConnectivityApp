package com.mywearapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.example.wearablecommunication.WearableCommunicationManager

class WearableCommunicationModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

    private var communicationManager: WearableCommunicationManager? = null
    private var isInitialized: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "WearableCommunicationModule"
    }

    override fun getName(): String = "WearableCommunicationModule"

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
            Log.d(TAG, "Already initialized, resolving promise")
            promise.resolve(true)
            return
        }

        try {
            initializeCommunication()
            promise.resolve(true)
            Log.d(TAG, "Initialize promise resolved")
        } catch (e: Exception) {
            Log.e(TAG, "Initialize failed: ${e.message}", e)
            promise.reject("INIT_ERROR", e.message, e)
        }
    }

    private fun initializeCommunication() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }
        
        Log.d(TAG, "initializeCommunication starting")
        
        try {
            communicationManager = WearableCommunicationManager(reactContext)

            // Set up callbacks BEFORE registering listeners
            communicationManager?.onMessageReceived = { message, fromMobile ->
                Log.d(TAG, "onMessageReceived: $message, fromMobile: $fromMobile")

                // Handle ping/pong protocol
                if (message.equals("ping", ignoreCase = true)) {
                    Log.d(TAG, "Received ping from mobile, sending ack")
                    val success = communicationManager?.sendMessageToMobile("ack")
                    Log.d(TAG, "ACK send result: $success")
                }

                // Send event to React Native
                mainHandler.post {
                    sendEvent("onMessageReceived", Arguments.createMap().apply {
                        putString("message", message)
                        putBoolean("fromMobile", fromMobile)
                    })
                }
            }

            communicationManager?.onMobileConnected = {
                Log.d(TAG, "onMobileConnected")
                mainHandler.post {
                    sendEvent("onMobileConnected", Arguments.createMap().apply {
                        putBoolean("connected", true)
                    })
                }
            }

            communicationManager?.onMessageSent = { success, msg ->
                Log.d(TAG, "onMessageSent: success=$success, msg=$msg")
                mainHandler.post {
                    sendEvent("onMessageSent", Arguments.createMap().apply {
                        putBoolean("success", success)
                        putString("message", msg ?: "")
                    })
                }
            }

            // Register listeners after setting up callbacks
            communicationManager?.registerListeners()
            isInitialized = true

            Log.d(TAG, "initializeCommunication completed successfully")
            
            // Send initialization complete event
            mainHandler.post {
                sendEvent("onInitialized", Arguments.createMap().apply {
                    putBoolean("success", true)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "initializeCommunication failed: ${e.message}", e)
            throw e
        }
    }

    @ReactMethod
    fun sendMessageToMobile(message: String, promise: Promise) {
        Log.d(TAG, "sendMessageToMobile: $message")
        
        if (!isInitialized || communicationManager == null) {
            Log.w(TAG, "Module not initialized")
            promise.reject("NOT_INITIALIZED", "Communication module not initialized")
            return
        }

        try {
            communicationManager?.sendMessageToMobile(message)
            promise.resolve(true)
            Log.d(TAG, "Message queued for sending: $message")
        } catch (e: Exception) {
            Log.e(TAG, "sendMessageToMobile failed: ${e.message}", e)
            promise.reject("SEND_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getConnectionStatus(promise: Promise) {
        Log.d(TAG, "getConnectionStatus called")
        
        try {
            val statusMap = Arguments.createMap().apply {
                putBoolean("initialized", isInitialized)
                putBoolean("managerExists", communicationManager != null)
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
            Log.d(TAG, "Cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}", e)
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        try {
            if (reactContext.hasActiveCatalystInstance()) {
                Log.d(TAG, "sendEvent: $eventName with params: $params")
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(eventName, params)
                Log.d(TAG, "Event sent successfully: $eventName")
            } else {
                Log.w(TAG, "Cannot send event $eventName - no active catalyst instance")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendEvent failed for $eventName: ${e.message}", e)
        }
    }

    override fun onCatalystInstanceDestroy() {
        Log.d(TAG, "onCatalystInstanceDestroy called")
        cleanup()
        super.onCatalystInstanceDestroy()
    }
}