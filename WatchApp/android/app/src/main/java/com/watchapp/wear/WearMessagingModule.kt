package com.watchapp.wear

import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

class WearMessagingModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext),
  MessageClient.OnMessageReceivedListener,
  LifecycleEventListener {

  companion object {
    private const val TAG = "WearMessagingModule"
    private const val DEBUG = true
    
    // Handshake paths
    private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private const val WEAR_APP_CHECK_PAYLOAD = "AppOpenWearable"
    private const val WEAR_APP_CHECK_ACK = "AppOpenWearableACK"
    
    private const val PHONE_TO_WEAR_PATH = "/message-item-received"  // Հեռախոսից ժամ
    private const val WEAR_TO_PHONE_PATH = "/wear-message-to-phone"  // Ժամից հեռախոս
  }

  

  private fun emitPendingMessages() {
    val prefs: SharedPreferences = reactContext.getSharedPreferences("wear_msg_store", Context.MODE_PRIVATE)
    val json = prefs.getString("pending_phone_to_watch", null) ?: return
    val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
    if (arr.length() == 0) return

    for (i in 0 until arr.length()) {
      val obj = arr.optJSONObject(i) ?: continue
      val path = obj.optString("path", "")
      val data = obj.optString("data", "")
      val sourceNodeId = obj.optString("sourceNodeId", "")
      val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

      if (path.isNotEmpty()) {
        val map = Arguments.createMap()
        map.putString("path", path)
        map.putString("sourceNodeId", sourceNodeId)
        map.putString("data", data)
        map.putDouble("timestamp", timestamp.toDouble())
        emit("WearMessage", map)
      }
    }

    // Clear after emitting
    prefs.edit().remove("pending_phone_to_watch").apply()
    if (DEBUG) Log.d(TAG, "Emitted and cleared pending messages: ${arr.length()}")
  }

  override fun getName(): String = "WearMessaging"

  init {
    reactContext.addLifecycleEventListener(this)
    try {
      Wearable.getMessageClient(reactContext).addListener(this)
      if (DEBUG) Log.d(TAG, "WearMessagingModule initialized")
    } catch (e: Exception) {
      if (DEBUG) Log.e(TAG, "Failed to initialize messaging client", e)
    }

    // Register broadcast receiver to forward background service messages to JS
    try {
      registerPhoneMessageReceiver()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to register phone message receiver on init", e)
    }

    // Drain any pending messages saved by the background service
    try {
      emitPendingMessages()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to emit pending messages on init", e)
    }
  }

  @ReactMethod
  fun addListener(@Suppress("UNUSED_PARAMETER") eventName: String) {
    // Required by RN event emitter contract
  }

  @ReactMethod
  fun removeListeners(@Suppress("UNUSED_PARAMETER") count: Int) {
    // Required by RN event emitter contract
  }

  @ReactMethod
  fun getConnectedNodes(promise: Promise) {
    Log.e(TAG, "Getting connected nodes")
    Wearable.getNodeClient(reactContext)
      .connectedNodes
      .addOnSuccessListener { nodes: List<Node> ->
        Log.e(TAG, "Found ${nodes.size} connected nodes")
        val result = Arguments.createArray()
        for (node in nodes) {
          val map = Arguments.createMap()
          map.putString("id", node.id)
          map.putString("displayName", node.displayName)
          map.putBoolean("isNearby", node.isNearby)
          result.pushMap(map)
          Log.e(TAG, "Node: ${node.displayName} (${node.id})")
        }
        promise.resolve(result)
      }
      .addOnFailureListener { e ->
        if (DEBUG) Log.e(TAG, "Failed to get connected nodes", e)
        promise.reject("E_WEAR_NODES", e.message, e)
      }
  }

  @ReactMethod
  fun sendMessageToPhone(message: String, promise: Promise) {
    if (DEBUG) Log.d(TAG, "Sending message to phone: $message")
    val payload = message.toByteArray(StandardCharsets.UTF_8)

    Wearable.getNodeClient(reactContext)
      .connectedNodes
      .addOnSuccessListener { nodes ->
        if (nodes.isNotEmpty()) {
          val phoneNodeId = nodes[0].id // Առաջին connected node-ը սովորաբար հեռախոսն է
          if (DEBUG) Log.d(TAG, "Sending to phone node: $phoneNodeId")
          
          Wearable.getMessageClient(reactContext)
            .sendMessage(phoneNodeId, WEAR_TO_PHONE_PATH, payload)
            .addOnSuccessListener { requestId ->
              Log.i(TAG, "SendSuccessToPhone")
              promise.resolve("Message sent to phone")
            }
            .addOnFailureListener { e ->
              if (DEBUG) Log.e(TAG, "Failed to send message", e)
              promise.reject("E_WEAR_SEND", e.message, e)
            }
        } else {
          if (DEBUG) Log.w(TAG, "No connected nodes found")
          promise.reject("E_WEAR_NO_NODES", "No connected phone found")
        }
      }
      .addOnFailureListener { e ->
        if (DEBUG) Log.e(TAG, "Failed to get connected nodes for sending", e)
        promise.reject("E_WEAR_NODES", e.message, e)
      }
  }

  @ReactMethod
  fun sendMessage(path: String, message: String, nodeId: String?, promise: Promise) {
    // Backward compatibility method
    Log.d(TAG, "Legacy sendMessage called with path: $path")
    if (path == WEAR_TO_PHONE_PATH || path == "/message-item-received") {
      sendMessageToPhone(message, promise)
    } else {
      val payload = message.toByteArray(StandardCharsets.UTF_8)
      
      fun sendToNode(targetNodeId: String) {
        Wearable.getMessageClient(reactContext)
          .sendMessage(targetNodeId, path, payload)
          .addOnSuccessListener { requestId ->
            promise.resolve(requestId.toString())
          }
          .addOnFailureListener { e ->
            promise.reject("E_WEAR_SEND", e.message, e)
          }
      }

      if (nodeId != null && nodeId.isNotEmpty()) {
        sendToNode(nodeId)
        return
      }

      Wearable.getNodeClient(reactContext)
        .connectedNodes
        .addOnSuccessListener { nodes ->
          if (nodes.isNotEmpty()) {
            sendToNode(nodes[0].id)
          } else {
            promise.reject("E_WEAR_NO_NODES", "No connected Wear OS nodes found")
          }
        }
        .addOnFailureListener { e ->
          promise.reject("E_WEAR_NODES", e.message, e)
        }
    }
  }

  override fun onMessageReceived(event: MessageEvent) {
    val path = event.path
    val data = String(event.data, StandardCharsets.UTF_8)
    val sourceNodeId = event.sourceNodeId
    
    Log.e(TAG, "Message received - Path: $path, Data: $data, Source: $sourceNodeId")

    when (path) {
      APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
        // Plain handshake: expect exact payload and reply with exact ACK
        if (data == WEAR_APP_CHECK_PAYLOAD) {
          Log.e(TAG, "Received handshake from phone, sending plain ACK")
          Wearable.getMessageClient(reactContext)
            .sendMessage(sourceNodeId, APP_OPEN_WEARABLE_PAYLOAD_PATH, WEAR_APP_CHECK_ACK.toByteArray(StandardCharsets.UTF_8))
            .addOnSuccessListener { if (DEBUG) Log.d(TAG, "ACK sent successfully") }
            .addOnFailureListener { e -> if (DEBUG) Log.e(TAG, "Failed to send ACK", e) }
        } else if (data == WEAR_APP_CHECK_ACK) {
          Log.e(TAG, "Received ACK from phone")
        }
        // Don't emit handshake messages to JS
        return
      }
      
      PHONE_TO_WEAR_PATH -> {
        Log.i(TAG, "ReceiveSuccessFromPhone:$data")
        // Message from phone to wear
      }
      
      else -> {
        Log.e(TAG, "Received message on path: $path")
      }
    }

    // Emit to React Native
    val map = Arguments.createMap()
    map.putString("path", path)
    map.putString("sourceNodeId", sourceNodeId)
    map.putString("data", data)
    map.putDouble("timestamp", System.currentTimeMillis().toDouble())
    emit("WearMessage", map)
  }

  // Broadcast receiver to capture messages forwarded by background service
  @Volatile private var receiverRegistered: Boolean = false
  private val phoneMsgReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != "com.watchapp.wear.MESSAGE_FROM_PHONE") return
      try {
        val path = intent.getStringExtra("path") ?: return
        val data = intent.getStringExtra("data") ?: ""
        val sourceNodeId = intent.getStringExtra("sourceNodeId") ?: ""
        if (DEBUG) Log.d(TAG, "Broadcast received - Path: $path, Data: $data, Source: $sourceNodeId")

        val map = Arguments.createMap()
        map.putString("path", path)
        map.putString("sourceNodeId", sourceNodeId)
        map.putString("data", data)
        map.putDouble("timestamp", System.currentTimeMillis().toDouble())
        emit("WearMessage", map)
      } catch (e: Exception) {
        Log.e(TAG, "Failed handling broadcast message", e)
      }
    }
  }

  private fun registerPhoneMessageReceiver() {
    if (receiverRegistered) return
    val filter = IntentFilter("com.watchapp.wear.MESSAGE_FROM_PHONE")
    try {
      reactContext.registerReceiver(phoneMsgReceiver, filter)
      receiverRegistered = true
      if (DEBUG) Log.d(TAG, "Phone message receiver registered")
    } catch (e: Exception) {
      Log.e(TAG, "Error registering phone message receiver", e)
    }
  }

  private fun unregisterPhoneMessageReceiver() {
    if (!receiverRegistered) return
    try {
      reactContext.unregisterReceiver(phoneMsgReceiver)
      receiverRegistered = false
      if (DEBUG) Log.d(TAG, "Phone message receiver unregistered")
    } catch (e: Exception) {
      Log.e(TAG, "Error unregistering phone message receiver", e)
    }
  }

  private fun emit(eventName: String, params: com.facebook.react.bridge.WritableMap) {
    try {
      reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit(eventName, params)
      Log.e(TAG, "Event emitted: $eventName")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to emit event: $eventName", e)
    }
  }

  override fun onHostResume() {
    Log.e(TAG, "Host resumed")
    try {
      // Guard against duplicate listeners across reloads/resumes
      Wearable.getMessageClient(reactContext).removeListener(this)
    } catch (e: Exception) {
      Log.w(TAG, "No existing listener to remove on resume (safe)", e)
    }
    try {
      Wearable.getMessageClient(reactContext).addListener(this)
      Log.e(TAG, "Re-attached MessageClient listener on resume")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to re-attach listener on resume", e)
    }

    // Ensure broadcast receiver is registered when app comes to foreground
    registerPhoneMessageReceiver()

    // Emit any messages received while app was backgrounded
    try {
      emitPendingMessages()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to emit pending messages on resume", e)
    }
  }
  
  override fun onHostPause() {
    Log.e(TAG, "Host paused")
  }
  
  override fun onHostDestroy() {
    Log.e(TAG, "Host destroyed, cleaning up")
    try {
      Wearable.getMessageClient(reactContext).removeListener(this)
    } catch (e: Exception) {
      Log.e(TAG, "Error removing message listener", e)
    }
    reactContext.removeLifecycleEventListener(this)

    // Unregister broadcast receiver to avoid leaks
    unregisterPhoneMessageReceiver()
  }
}