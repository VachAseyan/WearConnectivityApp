package com.watchapp.wear

import android.util.Log
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

class WearMessagingModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext),
  MessageClient.OnMessageReceivedListener,
  LifecycleEventListener {

  companion object {
    private const val TAG = "WearMessagingModule"
    
    // Handshake paths
    private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private const val WEAR_APP_CHECK_PAYLOAD = "AppOpenWearable"
    private const val WEAR_APP_CHECK_ACK = "AppOpenWearableACK"
    
    // Message paths - առանձին paths բեռնիքի և ժամի համար
    private const val PHONE_TO_WEAR_PATH = "/message-item-received"  // Հեռախոսից ժամ
    private const val WEAR_TO_PHONE_PATH = "/wear-message-to-phone"  // Ժամից հեռախոս
  }

  override fun getName(): String = "WearMessaging"

  init {
    reactContext.addLifecycleEventListener(this)
    try {
      Wearable.getMessageClient(reactContext).addListener(this)
      Log.d(TAG, "WearMessagingModule initialized")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize messaging client", e)
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
    Log.d(TAG, "Getting connected nodes")
    Wearable.getNodeClient(reactContext)
      .connectedNodes
      .addOnSuccessListener { nodes: List<Node> ->
        Log.d(TAG, "Found ${nodes.size} connected nodes")
        val result = Arguments.createArray()
        for (node in nodes) {
          val map = Arguments.createMap()
          map.putString("id", node.id)
          map.putString("displayName", node.displayName)
          map.putBoolean("isNearby", node.isNearby)
          result.pushMap(map)
          Log.d(TAG, "Node: ${node.displayName} (${node.id})")
        }
        promise.resolve(result)
      }
      .addOnFailureListener { e ->
        Log.e(TAG, "Failed to get connected nodes", e)
        promise.reject("E_WEAR_NODES", e.message, e)
      }
  }

  @ReactMethod
  fun sendMessageToPhone(message: String, promise: Promise) {
    Log.d(TAG, "Sending message to phone: $message")
    val payload = message.toByteArray(StandardCharsets.UTF_8)

    Wearable.getNodeClient(reactContext)
      .connectedNodes
      .addOnSuccessListener { nodes ->
        if (nodes.isNotEmpty()) {
          val phoneNodeId = nodes[0].id // Առաջին connected node-ը սովորաբար հեռախոսն է
          Log.d(TAG, "Sending to phone node: $phoneNodeId")
          
          Wearable.getMessageClient(reactContext)
            .sendMessage(phoneNodeId, WEAR_TO_PHONE_PATH, payload)
            .addOnSuccessListener { requestId ->
              Log.d(TAG, "Message sent successfully, requestId: $requestId")
              promise.resolve("Message sent to phone")
            }
            .addOnFailureListener { e ->
              Log.e(TAG, "Failed to send message", e)
              promise.reject("E_WEAR_SEND", e.message, e)
            }
        } else {
          Log.w(TAG, "No connected nodes found")
          promise.reject("E_WEAR_NO_NODES", "No connected phone found")
        }
      }
      .addOnFailureListener { e ->
        Log.e(TAG, "Failed to get connected nodes for sending", e)
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
    
    Log.d(TAG, "Message received - Path: $path, Data: $data, Source: $sourceNodeId")

    when (path) {
      APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
        // Expect payload like "AppOpenWearable:<nonce>"; reply with "AppOpenWearableACK:<nonce>"
        if (data.startsWith(WEAR_APP_CHECK_PAYLOAD)) {
          val parts = data.split(":", limit = 2)
          val nonce = if (parts.size == 2) parts[1] else ""
          val ack = if (nonce.isNotEmpty()) "$WEAR_APP_CHECK_ACK:$nonce" else WEAR_APP_CHECK_ACK
          Log.d(TAG, "Received handshake from phone, sending ACK with nonce: $nonce")
          // Reply to handshake so phone can confirm the watch app is open
          Wearable.getMessageClient(reactContext)
            .sendMessage(sourceNodeId, APP_OPEN_WEARABLE_PAYLOAD_PATH, ack.toByteArray(StandardCharsets.UTF_8))
            .addOnSuccessListener {
              Log.d(TAG, "ACK sent successfully")
            }
            .addOnFailureListener { e ->
              Log.e(TAG, "Failed to send ACK", e)
            }
        } else if (data.startsWith(WEAR_APP_CHECK_ACK)) {
          Log.d(TAG, "Received ACK from phone")
        }
        // Don't emit handshake messages to JS
        return
      }
      
      PHONE_TO_WEAR_PATH -> {
        Log.d(TAG, "Received message from phone: $data")
        // Message from phone to wear
      }
      
      else -> {
        Log.d(TAG, "Received message on path: $path")
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

  private fun emit(eventName: String, params: com.facebook.react.bridge.WritableMap) {
    try {
      reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit(eventName, params)
      Log.d(TAG, "Event emitted: $eventName")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to emit event: $eventName", e)
    }
  }

  override fun onHostResume() {
    Log.d(TAG, "Host resumed")
  }
  
  override fun onHostPause() {
    Log.d(TAG, "Host paused")
  }
  
  override fun onHostDestroy() {
    Log.d(TAG, "Host destroyed, cleaning up")
    try {
      Wearable.getMessageClient(reactContext).removeListener(this)
    } catch (e: Exception) {
      Log.e(TAG, "Error removing message listener", e)
    }
    reactContext.removeLifecycleEventListener(this)
  }
}