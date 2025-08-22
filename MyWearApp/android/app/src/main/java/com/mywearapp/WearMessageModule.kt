package com.mywearapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import android.bluetooth.BluetoothAdapter
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
    private const val DEBUG = false
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
    if (DEBUG) Log.d(TAG, "Initializing WearMessageModule")
    Wearable.getMessageClient(reactCtx).addListener(this)
    Wearable.getCapabilityClient(reactCtx)
      .addListener(this, Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE)

    updateConnectedNodes()
  }

  override fun onCatalystInstanceDestroy() {
    if (DEBUG) Log.d(TAG, "Destroying WearMessageModule")
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
      Log.e("Hello","checkConnection")
    try {
      // Fetch nodes fresh to avoid race conditions with async updates
      Wearable.getNodeClient(reactCtx)
        .connectedNodes
        .addOnSuccessListener { nodes ->
          val btEnabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
          val nearbyNodes = nodes.filter { it.isNearby }

          if (!btEnabled) {
            if (DEBUG) Log.d(TAG, "Bluetooth disabled; treating as disconnected")
            promise.resolve(false)
            return@addOnSuccessListener
          }

          if (nearbyNodes.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No nearby nodes; treating as disconnected")
            promise.resolve(false)
            return@addOnSuccessListener
          }

          lastAck = null
          lastNodeId = null

          if (DEBUG) Log.d(TAG, "Checking BLE connection with ${nearbyNodes.size} nearby node(s)")

          val messageClient = Wearable.getMessageClient(reactCtx)

          // Register pending promise so onMessageReceived can resolve immediately on ACK
          pendingConnectionPromise = promise

          var resolved = false
          fun attempt(attempt: Int, maxAttempts: Int) {
            if (resolved) return
            // Clear any stale ACK from previous attempt
            lastAck = null
            HandshakeStore.clear()
            // Send plain handshake like the working Kotlin sample
            val payloadString = WEAR_APP_CHECK_PAYLOAD_PREFIX
            expectedAck = WEAR_APP_CHECK_ACK_PREFIX
            val payload = payloadString.toByteArray(StandardCharsets.UTF_8)

            if (DEBUG) Log.d(TAG, "Handshake attempt ${attempt + 1}/$maxAttempts (BLE nearby only) with expectedAck=$expectedAck")
            nearbyNodes.forEach { node ->
              if (DEBUG) Log.d(TAG, "Sending handshake to node: ${node.id} (${node.displayName})")
              messageClient
                .sendMessage(node.id, APP_OPEN_WEARABLE_PAYLOAD_PATH, payload)
                .addOnSuccessListener { if (DEBUG) Log.d(TAG, "Handshake dispatched to ${node.id}") }
                .addOnFailureListener { e -> if (DEBUG) Log.e(TAG, "Failed to send handshake to ${node.id}", e) }
            }

            mainHandler.postDelayed({
              if (resolved) return@postDelayed
              // If promise already resolved (via onMessageReceived), stop
              val pending = pendingConnectionPromise
              if (pending == null) return@postDelayed
              val ackSeen = lastAck ?: HandshakeStore.lastAck
              val isConnected = ackSeen == expectedAck
              if (isConnected) {
                Log.i(TAG, "ConnectionSuccess")
                resolved = true
                pendingConnectionPromise = null
                pending.resolve(true)
              } else if (attempt + 1 < maxAttempts) {
                if (DEBUG) Log.d(TAG, "No ACK yet, retrying... attempt ${attempt + 2}")
                attempt(attempt + 1, maxAttempts)
              } else {
                if (DEBUG) Log.e(TAG, "Handshake failed after $maxAttempts attempts. lastAckInModule=$lastAck lastAckInStore=${HandshakeStore.lastAck} expectedAck=$expectedAck")
                resolved = true
                pendingConnectionPromise = null
                pending.resolve(false)
              }
            }, CONNECTION_TIMEOUT)
          }

          attempt(0, 3)
        }
        .addOnFailureListener { e ->
          if (DEBUG) Log.e(TAG, "Failed to load connected nodes for handshake", e)
          promise.reject("NODES_FETCH_ERROR", e.message, e)
        }
    } catch (e: Exception) {
      if (DEBUG) Log.e(TAG, "Connection check error", e)
      promise.reject("CHECK_CONNECTION_ERROR", e.message, e)
    }
  }

  @ReactMethod
  fun sendMessageToWear(message: String, promise: Promise) {
      Log.e("sendmessagetowear","hello")
    try {
      // Fetch fresh connected nodes to avoid stale cache and ensure watch is targeted
      Wearable.getNodeClient(reactCtx)
        .connectedNodes
        .addOnSuccessListener { nodes ->
          val nearby = nodes.filter { it.isNearby }
          val targets = if (nearby.isNotEmpty()) nearby else nodes

          if (targets.isEmpty()) {
            promise.reject("NO_CONNECTED_NODES", "No connected wearable device found")
            return@addOnSuccessListener
          }

          Log.d(TAG, "Sending to ${targets.size} node(s). Payload='$message'")

          val messageClient = Wearable.getMessageClient(reactCtx)
          var resolved = false
          var failures = 0
          val payload = message.toByteArray(StandardCharsets.UTF_8)

          targets.forEach { node ->
            messageClient
              .sendMessage(node.id, MESSAGE_ITEM_RECEIVED_PATH, payload)
              .addOnSuccessListener {
                if (!resolved) {
                  Log.i(TAG, "SendSuccessToWatch node=${node.id}")
                  resolved = true
                  promise.resolve("Message sent to wearable (${node.displayName ?: node.id})")
                }
              }
              .addOnFailureListener { e ->
                failures += 1
                Log.w(TAG, "Failed to send to node=${node.id}", e)
                if (!resolved && failures >= targets.size) {
                  promise.reject("SEND_ERROR", "Failed to send to all connected nodes", e)
                }
              }
          }
        }
        .addOnFailureListener { e ->
          Log.e(TAG, "❌ Failed to load connected nodes for send", e)
          promise.reject("NODES_FETCH_ERROR", e.message, e)
        }
    } catch (e: Exception) {
      Log.e(TAG, "❌ Send message error", e)
      promise.reject("SEND_ERROR", e.message, e)
    }
  }

  @ReactMethod
  fun getConnectedNodes(promise: Promise) {
    Log.e("Connected Nodes","yes")
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

  // sendToNode no longer used; sending to all targets above for reliability

  override fun onMessageReceived(messageEvent: MessageEvent) {
    try {
      val eventPath = messageEvent.path
      val message = String(messageEvent.data, StandardCharsets.UTF_8)
      val sourceNodeId = messageEvent.sourceNodeId

      if (DEBUG) {
        Log.d(TAG, "Received event path=$eventPath msg=$message src=$sourceNodeId expectedAck=$expectedAck")
      }

      when (eventPath) {
        APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
          lastAck = message
          lastNodeId = sourceNodeId
          HandshakeStore.lastAck = message
          HandshakeStore.lastNodeId = sourceNodeId

          if (message == expectedAck) {
            Log.i(TAG, "ConnectionSuccess")
            // If handshake check is in progress, resolve immediately
            pendingConnectionPromise?.let { pending ->
              if (DEBUG) Log.d(TAG, "Resolving pending connection promise immediately")
              pendingConnectionPromise = null
              try {
                pending.resolve(true)
              } catch (e: Exception) {
                if (DEBUG) Log.w(TAG, "Pending promise resolve failed (already handled?)", e)
              }
            }
          } else {
            if (DEBUG) Log.w(TAG, "Wrong ACK received. Expected: '$expectedAck', Got: '$message'")
          }
          return // Don't show handshake in UI
        }

        WEAR_TO_PHONE_PATH -> {
          Log.i(TAG, "ReceiveSuccessFromWatch:$message")
        }

        MESSAGE_ITEM_RECEIVED_PATH -> {
          Log.d(TAG, "📨 Legacy path message: $message")
        }

        else -> { if (DEBUG) Log.d(TAG, "Unknown path on phone: $eventPath") }
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

    } catch (e: Exception) { if (DEBUG) Log.e(TAG, "Failed to process message event", e) }
  }

  override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
    if (DEBUG) Log.d(TAG, "Capability changed: ${capabilityInfo.name}, nodes: ${capabilityInfo.nodes.size}")

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