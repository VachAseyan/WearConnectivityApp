package com.watchapp

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
            promise.resolve(true)
            return
        }

        mainHandler.post {
            try {
                initializeCommunication()
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Initialize failed: ${e.message}", e)
                promise.reject("INIT_ERROR", e.message, e)
            }
        }
    }

    private fun initializeCommunication() {
        if (isInitialized) {
            return
        }
        
        Log.d(TAG, "initializeCommunication starting")
        
        communicationManager = WearableCommunicationManager(reactContext)

        

        // Set up callbacks BEFORE registering listeners
        communicationManager?.onMessageReceived = { message, fromMobile ->
            Log.d(TAG, "onMessageReceived: $message, fromMobile: $fromMobile")

            if (message == "ping" || message.startsWith("/ping")) {
            Log.d(TAG, "Received ping from mobile, sending ack")
            // Send acknowledgment back to mobile
            communicationManager?.sendMessageToMobile("ack")
        }

            
            sendEvent("onMessageReceived", Arguments.createMap().apply {
                putString("message", message)
                putBoolean("fromMobile", fromMobile)
            })
        }

        communicationManager?.onMobileConnected = {
            Log.d(TAG, "onMobileConnected")
            sendEvent("onMobileConnected", Arguments.createMap())
        }

        communicationManager?.onMessageSent = { success, msg ->
            Log.d(TAG, "onMessageSent: success=$success, msg=$msg")
            sendEvent("onMessageSent", Arguments.createMap().apply {
                putBoolean("success", success)
                putString("message", msg ?: "")
            })
        }

        // Register listeners after setting up callbacks
        communicationManager?.registerListeners()
        isInitialized = true

        Log.d(TAG, "initializeCommunication completed")
        
        // Send initialization complete event
        sendEvent("onInitialized", Arguments.createMap().apply {
            putBoolean("success", true)
        })
    }

    @ReactMethod
    fun sendMessageToMobile(message: String, promise: Promise) {
        Log.d(TAG, "sendMessageToMobile: $message")
        
        if (!isInitialized || communicationManager == null) {
            Log.w(TAG, "Module not initialized, initializing now...")
            initializeCommunication()
        }

        mainHandler.post {
            try {
                communicationManager?.sendMessageToMobile(message)
                // Promise resolve անենք անմիջապես, իսկ actual result-ը կգա onMessageSent callback-ով
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "sendMessageToMobile failed: ${e.message}", e)
                promise.reject("SEND_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun isModuleInitialized(callback: Callback) {
        Log.d(TAG, "isModuleInitialized: $isInitialized")
        callback.invoke(isInitialized)
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