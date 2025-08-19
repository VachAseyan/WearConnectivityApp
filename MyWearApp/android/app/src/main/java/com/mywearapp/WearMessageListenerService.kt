package com.mywearapp

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

class WearMessageListenerService : WearableListenerService() {
  companion object {
    private const val TAG = "WearMsgBgService"
    private const val APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD"
    private const val WEAR_TO_PHONE_PATH = "/wear-message-to-phone"
    private const val WEAR_APP_CHECK_PAYLOAD_PREFIX = "AppOpenWearable"
    private const val WEAR_APP_CHECK_ACK_PREFIX = "AppOpenWearableACK"
  }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    val path = messageEvent.path
    val data = String(messageEvent.data, StandardCharsets.UTF_8)
    val sourceNodeId = messageEvent.sourceNodeId

    Log.d(TAG, "[BG] onMessageReceived path=$path data=$data from=$sourceNodeId")

    when (path) {
      APP_OPEN_WEARABLE_PAYLOAD_PATH -> {
        // Handle both request and ACK on background
        when {
          data.startsWith(WEAR_APP_CHECK_PAYLOAD_PREFIX) -> {
            val parts = data.split(":", limit = 2)
            val nonce = if (parts.size == 2) parts[1] else ""
            val ack = if (nonce.isNotEmpty()) "$WEAR_APP_CHECK_ACK_PREFIX:$nonce" else WEAR_APP_CHECK_ACK_PREFIX
            Log.d(TAG, "[BG] Handshake request from watch, sending ACK nonce=$nonce")
            Wearable.getMessageClient(this)
              .sendMessage(sourceNodeId, APP_OPEN_WEARABLE_PAYLOAD_PATH, ack.toByteArray(StandardCharsets.UTF_8))
              .addOnSuccessListener { Log.d(TAG, "[BG] ACK sent") }
              .addOnFailureListener { e -> Log.e(TAG, "[BG] Failed to send ACK", e) }
          }
          data.startsWith(WEAR_APP_CHECK_ACK_PREFIX) -> {
            // Store ACK so the module's handshake check can succeed
            HandshakeStore.lastAck = data
            HandshakeStore.lastNodeId = sourceNodeId
            Log.d(TAG, "[BG] Stored ACK from watch: ${HandshakeStore.lastAck}")
          }
        }
      }
      WEAR_TO_PHONE_PATH -> {
        Log.d(TAG, "[BG] Message from watch: $data")
        // Optionally: persist or notify. For now, rely on app-level listener when app is open.
      }
      else -> {
        Log.d(TAG, "[BG] Unknown path: $path")
      }
    }
  }
}
