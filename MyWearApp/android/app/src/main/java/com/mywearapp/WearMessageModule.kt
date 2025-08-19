package com.mywearapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.nio.charset.StandardCharsets

class WearMessageModule(private val reactCtx: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactCtx), MessageClient.OnMessageReceivedListener,
  CapabilityClient.OnCapabilityChangedListener {

  companion object {
    private const val TAG = "WearMessageModule"
    private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private const val MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received"
    private const val WEAR_TO_PHONE_PATH = "/wear-message-to-phone"
    private const val WEAR_APP_CHECK_PAYLOAD_PREFIX = "AppOpenWearable"
    private const val WEAR_APP_CHECK_ACK_PREFIX = "AppOpenWearableACK"
    private const val CONNECTION_TIMEOUT = 10000L
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var lastAck: String? = null
  @Volatile private var lastNodeId: String? = null
  @Volatile private var connectedNodes: Set<String> = emptySet()
  @Volatile private var expectedAck: String? = null
  @Volatile private var pendingConnectionPromise: Promise? = null

  override fun getName(): String = "WearMessage"

  override fun initialize() {
    super.initialize()
    Log.d(TAG, "🔧 Initializing WearMessageModule")
    Wearable.getMessageClient(reactCtx).addListener(this)
    Wearable.getCapabilityClient(reactCtx)
      .addListener(this, Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE)

    updateConnectedNodes()
  }

  override fun onCatalystInstanceDestroy() {
    Log.d(TAG, "🔴 Destroying WearMessageModule")
    Wearable.getMessageClient(reactCtx).removeListener(this)
    Wearable.getCapabilityClient(reactCtx).removeListener(this)
    super.onCatalystInstanceDestroy()
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
  fun checkConnection(promise: Promise) {
    try {
      // Fetch nodes fresh to avoid race conditions with async updates
      Wearable.getNodeClient(reactCtx)
        .connectedNodes
        .addOnSuccessListener { nodes ->
          if (nodes.isEmpty()) {
            Log.d(TAG, "❌ No connected nodes found")
            promise.resolve(false)
            return@addOnSuccessListener
          }

          lastAck = null
          lastNodeId = null

          Log.d(TAG, "🤝 Checking connection with ${nodes.size} node(s)")

          val messageClient = Wearable.getMessageClient(reactCtx)

          // Register pending promise so onMessageReceived can resolve immediately on ACK
          pendingConnectionPromise = promise

          var resolved = false
          fun attempt(attempt: Int, maxAttempts: Int) {
            if (resolved) return
            // Clear any stale ACK from previous attempt before generating a new nonce
            lastAck = null
            HandshakeStore.clear()
            val nonce = System.currentTimeMillis().toString()
            val payloadString = "$WEAR_APP_CHECK_PAYLOAD_PREFIX:$nonce"
            expectedAck = "$WEAR_APP_CHECK_ACK_PREFIX:$nonce"
            val payload = payloadString.toByteArray(StandardCharsets.UTF_8)

            Log.d(TAG, "📤 Handshake attempt ${attempt + 1}/$maxAttempts with expectedAck=$expectedAck")
            nodes.forEach { node ->
              Log.d(TAG, "📤 Sending handshake to node: ${node.id} (${node.displayName})")
              messageClient
                .sendMessage(node.id, APP_OPEN_WEARABLE_PAYLOAD_PATH, payload)
                .addOnSuccessListener { Log.d(TAG, "✅ Handshake dispatched to ${node.id}") }
                .addOnFailureListener { e -> Log.e(TAG, "❌ Failed to send handshake to ${node.id}", e) }
            }

            mainHandler.postDelayed({
              if (resolved) return@postDelayed
              // If promise already resolved (via onMessageReceived), stop
              val pending = pendingConnectionPromise
              if (pending == null) return@postDelayed
              val ackSeen = lastAck ?: HandshakeStore.lastAck
              val isConnected = ackSeen == expectedAck
              if (isConnected) {
                Log.d(TAG, "🔍 Connection OK on attempt ${attempt + 1}: expectedAck=$expectedAck lastAckInModule=$lastAck lastAckInStore=${HandshakeStore.lastAck}")
                resolved = true
                pendingConnectionPromise = null
                pending.resolve(true)
              } else if (attempt + 1 < maxAttempts) {
                Log.w(TAG, "⏳ No ACK yet, retrying... attempt ${attempt + 2}")
                attempt(attempt + 1, maxAttempts)
              } else {
                Log.e(TAG, "❌ Handshake failed after $maxAttempts attempts. lastAckInModule=$lastAck lastAckInStore=${HandshakeStore.lastAck} expectedAck=$expectedAck")
                resolved = true
                pendingConnectionPromise = null
                pending.resolve(false)
              }
            }, CONNECTION_TIMEOUT)
          }

          attempt(0, 3)
        }
        .addOnFailureListener { e ->
          Log.e(TAG, "❌ Failed to load connected nodes for handshake", e)
          promise.reject("NODES_FETCH_ERROR", e.message, e)
        }
    } catch (e: Exception) {
      Log.e(TAG, "❌ Connection check error", e)
      promise.reject("CHECK_CONNECTION_ERROR", e.message, e)
    }
  }

  @ReactMethod
  fun sendMessageToWear(message: String, promise: Promise) {
    try {
      if (connectedNodes.isEmpty()) {
        updateConnectedNodes()
        if (connectedNodes.isEmpty()) {
          promise.reject("NO_CONNECTED_NODES", "No connected wearable device found")
          return
        }
      }

      val nodeId = connectedNodes.first()
      Log.d(TAG, "📱➡️⌚ Sending message to node: $nodeId, message: $message")

      sendToNode(nodeId, message, promise)
    } catch (e: Exception) {
      Log.e(TAG, "❌ Send message error", e)
      promise.reject("SEND_ERROR", e.message, e)
    }
  }

  @ReactMethod
  fun getConnectedNodes(promise: Promise) {
    try {
      Wearable.getNodeClient(reactCtx).connectedNodes
        .addOnSuccessListener { nodes ->
          val nodeList = Arguments.createArray()
          nodes.forEach { node ->
            val nodeMap = Arguments.createMap().apply {
              putString("id", node.id)
              putString("displayName", node.displayName)
              putBoolean("isNearby", node.isNearby)
            }
            nodeList.pushMap(nodeMap)
          }
          promise.resolve(nodeList)
        }
        .addOnFailureListener { e ->
          promise.reject("NODES_FETCH_ERROR", e.message, e)
        }
    } catch (e: Exception) {
      promise.reject("NODES_FETCH_ERROR", e.message, e)
    }
  }

  private fun updateConnectedNodes() {
    Wearable.getNodeClient(reactCtx).connectedNodes
      .addOnSuccessListener { nodes ->
        connectedNodes = nodes.map { it.id }.toSet()
        Log.d(TAG, "📱 Connected nodes updated: ${connectedNodes.size} nodes")

        val params = Arguments.createMap().apply {
          putInt("count", connectedNodes.size)
          putBoolean("hasConnection", connectedNodes.isNotEmpty())
        }
        reactApplicationContext
          .getJSModule(RCTDeviceEventEmitter::class.java)
          .emit("WearNodesChanged", params)
      }
      .addOnFailureListener { e ->
        Log.e(TAG, "❌ Failed to update connected nodes", e)
        connectedNodes = emptySet()
      }
  }

  private fun sendToNode(nodeId: String, message: String, promise: Promise) {
    val payload = message.toByteArray(StandardCharsets.UTF_8)
    Wearable.getMessageClient(reactCtx)
      .sendMessage(nodeId, MESSAGE_ITEM_RECEIVED_PATH, payload)
      .addOnSuccessListener {
        Log.d(TAG, "✅ Message sent successfully to $nodeId")
        promise.resolve("Message sent to wearable")
      }
      .addOnFailureListener { e ->
        Log.e(TAG, "❌ Failed to send message to $nodeId", e)
        promise.reject("SEND_ERROR", e.message, e)
      }
  }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    try {
      val eventPath = messageEvent.path
      val message = String(messageEvent.data, StandardCharsets.UTF_8)
      val sourceNodeId = messageEvent.sourceNodeId

      Log.d(TAG, "🔔 ===== PHONE RECEIVED MESSAGE =====")
      Log.d(TAG, "🔔 Path: $eventPath")
      Log.d(TAG, "🔔 Message: $message")
      Log.d(TAG, "🔔 Source: $sourceNodeId")
      Log.d(TAG, "🔔 Expected ACK: $expectedAck")

      Log.d(TAG, "🔔 ===================================")

      when (eventPath) {
        APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
          Log.d(TAG, "🤝 Handshake path matched on phone!")
          lastAck = message
          lastNodeId = sourceNodeId
          HandshakeStore.lastAck = message
          HandshakeStore.lastNodeId = sourceNodeId
          Log.d(TAG, "🤝 Stored ACK: $message from $sourceNodeId")

          if (message == expectedAck) {
            Log.d(TAG, "✅ CORRECT ACK RECEIVED!")
            // If handshake check is in progress, resolve immediately
            pendingConnectionPromise?.let { pending ->
              Log.d(TAG, "✅ Resolving pending connection promise immediately")
              pendingConnectionPromise = null
              try {
                pending.resolve(true)
              } catch (e: Exception) {
                Log.w(TAG, "Pending promise resolve failed (already handled?)", e)
              }
            }
          } else {
            Log.w(TAG, "⚠️ Wrong ACK received. Expected: '$expectedAck', Got: '$message'")
          }
          return // Don't show handshake in UI
        }

        WEAR_TO_PHONE_PATH -> {
          Log.d(TAG, "⌚➡️📱 Message from watch: $message")
        }

        MESSAGE_ITEM_RECEIVED_PATH -> {
          Log.d(TAG, "📨 Legacy path message: $message")
        }

        else -> {
          Log.d(TAG, "❓ Unknown path on phone: $eventPath")
        }
      }

      // Emit to React Native
      val params = Arguments.createMap().apply {
        putString("path", eventPath)
        putString("message", message)
        putString("sourceNodeId", sourceNodeId)
        putDouble("timestamp", System.currentTimeMillis().toDouble())
      }

      reactApplicationContext
        .getJSModule(RCTDeviceEventEmitter::class.java)
        .emit("WearMessage", params)

    } catch (e: Exception) {
      Log.e(TAG, "❌ Failed to process message event", e)
    }
  }

  override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
    Log.d(TAG, "🔄 Capability changed: ${capabilityInfo.name}, nodes: ${capabilityInfo.nodes.size}")

    updateConnectedNodes()

    val params = Arguments.createMap().apply {
      putString("name", capabilityInfo.name)
      putBoolean("reachable", capabilityInfo.nodes.isNotEmpty())
      putInt("nodeCount", capabilityInfo.nodes.size)
    }

    reactApplicationContext
      .getJSModule(RCTDeviceEventEmitter::class.java)
      .emit("WearCapabilityChanged", params)
  }
}